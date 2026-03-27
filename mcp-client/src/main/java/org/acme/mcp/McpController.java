package org.acme.mcp;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.acme.mcp.model.JsonRpcRequest;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.smallrye.common.annotation.Blocking;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import org.acme.mcp.service.UserModelCache;
import org.eclipse.microprofile.jwt.JsonWebToken;

import jakarta.annotation.PostConstruct;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Path("/api/mcp")
public class McpController {

    @Inject
    JsonWebToken jwt;

    @Inject
    UserModelCache cache;

    // Static model removed because we use per-user cache now.
    // private ChatLanguageModel chatModel;

    // initIA() removed since each request uses its own model from cache.

    private static final Map<String, ActiveSession> activeSessions = new ConcurrentHashMap<>();

    public static class ActiveSession {
        public McpWeatherClient messageClient;
        public AtomicReference<String> lastMessage = new AtomicReference<>();
        public AtomicReference<CountDownLatch> messageLatch = new AtomicReference<>(new CountDownLatch(1));
        public List<Map<String, Object>> cachedTools = new ArrayList<>();
    }

    @POST
    @Path("/connect")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object connectToServer(Map<String, Object> payload) {
        String targetUrl = (String) payload.get("url");

        // 1. Prevención de Caché Envenenada (de la rama remota)
        if (activeSessions.containsKey(targetUrl)) {
            if (activeSessions.get(targetUrl).cachedTools.isEmpty()) {
                activeSessions.remove(targetUrl);
            } else {
                return Map.of("tools", activeSessions.get(targetUrl).cachedTools);
            }
        }

        try {
            McpWeatherClient sseClient = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(targetUrl))
                    .build(McpWeatherClient.class);

            ActiveSession newSession = new ActiveSession();
            AtomicReference<String> sessionUrl = new AtomicReference<>();
            CountDownLatch endpointLatch = new CountDownLatch(1);

            sseClient.connectToSse().subscribe().with(event -> {
                if ("endpoint".equals(event.name())) {
                    System.out.println("✅ SSE Endpoint recibido: " + event.data());
                    sessionUrl.set(event.data());
                    endpointLatch.countDown();
                } else if ("message".equals(event.name())) {
                    newSession.lastMessage.set(event.data());
                    newSession.messageLatch.get().countDown();
                }
            }, f -> {
                System.err.println("❌ Error en suscripción SSE: " + f.getMessage());
            });

            // Usamos nuestro timeout de 10s (más seguro en Docker)
            if (!endpointLatch.await(10, TimeUnit.SECONDS)) {
                System.err.println("❌ Timeout esperando endpoint SSE para: " + targetUrl);
                return Map.of("error", "Timeout SSE. Verifica que la URL sea accesible desde el contenedor (usa host.docker.internal si el servidor está fuera de Docker).");
            }

            // 2. SOLUCIÓN DE RUTAS UNIVERSALES: Usamos URI.resolve() (de la rama remota)
            URI baseUri = new URI(targetUrl);
            URI messageUri = baseUri.resolve(sessionUrl.get());

            newSession.messageClient = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(messageUri)
                    .build(McpWeatherClient.class);

            activeSessions.put(targetUrl, newSession);

            newSession.messageLatch.set(new CountDownLatch(1));
            newSession.messageClient.sendMessage(new JsonRpcRequest(1, "initialize",
                    Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "QuarkusClient", "version", "1.0"))));
            newSession.messageLatch.get().await(5, TimeUnit.SECONDS);

            Object toolsResponse = askForTools(targetUrl);

            // Si fallamos pidiendo herramientas, no nos guardamos la sesión rota (de la rama remota)
            if (toolsResponse instanceof Map && ((Map) toolsResponse).containsKey("error")) {
                activeSessions.remove(targetUrl);
            }
            return toolsResponse;

        } catch (Exception e) {
            activeSessions.remove(targetUrl);
            return Map.of("error", "Error de conexión: " + e.getMessage());
        }
    }

    private Object askForTools(String targetUrl) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            ActiveSession session = activeSessions.get(targetUrl);
            session.messageLatch.set(new CountDownLatch(1));
            session.messageClient.sendMessage(new JsonRpcRequest(3, "tools/list", Map.of()));
            session.messageLatch.get().await(5, TimeUnit.SECONDS);

            String raw = session.lastMessage.get();
            if (raw == null) {
                return Map.of("error", "No se recibieron herramientas del servidor");
            }

            Map<String, Object> fullRes = mapper.readValue(raw, Map.class);
            Map<String, Object> result = (Map<String, Object>) fullRes.get("result");
            if (result != null && result.containsKey("tools")) {
                session.cachedTools = (List<Map<String, Object>>) result.get("tools");
            }
            return raw;
        } catch (Exception e) {
            return Map.of("error", "Error pidiendo herramientas: " + e.getMessage());
        }
    }

    @POST
    @Path("/execute")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object executeTool(Map<String, Object> payload) {
        String targetUrl = (String) payload.get("url");
        ActiveSession session = activeSessions.get(targetUrl);
        try {
            session.messageLatch.set(new CountDownLatch(1));
            session.messageClient.sendMessage(new JsonRpcRequest(4, "tools/call", Map.of("name", payload.get("toolName"), "arguments", payload.get("arguments"))));
            session.messageLatch.get().await(10, TimeUnit.SECONDS);
            return session.lastMessage.get();
        } catch (Exception e) { return Map.of("error", e.getMessage()); }
    }

    @POST
    @Path("/chat")
    @Blocking
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object chatWithAgent(Map<String, Object> payload) {
        String userMessage = (String) payload.get("message");
        UUID userId = UUID.fromString(jwt.getSubject());
        ChatLanguageModel userChatModel = cache.getModel(userId);
        
        ObjectMapper mapper = new ObjectMapper();
        try {
            List<Object> allTools = new ArrayList<>();
            Map<String, String> toolMap = new HashMap<>();

            for (var entry : activeSessions.entrySet()) {
                for (var t : entry.getValue().cachedTools) {
                    allTools.add(t);
                    toolMap.put((String) t.get("name"), entry.getKey());
                }
            }

            if (allTools.isEmpty()) {
                return Map.of("reply", "No detecto herramientas conectadas.");
            }

            String toolsJson = mapper.writeValueAsString(allTools);
            // Memoria acumulativa para el bucle
            String conversationHistory = "Usuario: " + userMessage;
            List<Map<String, Object>> executedTools = new ArrayList<>();

            int maxSteps = 5;

            for (int step = 0; step < maxSteps; step++) {
                String prompt = "Eres un orquestador IA avanzado. " +
                        "Historial de la investigación actual: " + conversationHistory + ". " +
                        "Herramientas disponibles: " + toolsJson + ". " +
                        "REGLA OBLIGATORIA: Responde SOLO con un JSON válido. " +
                        "Si necesitas más datos de las herramientas para responder a la petición original, devuelve: { \"action\": \"tool\", \"toolName\": \"...\", \"arguments\": {...} }. " +
                        "Si YA TIENES todos los datos necesarios en el historial, responde FINALMENTE al usuario devolviendo: { \"action\": \"reply\", \"message\": \"tu respuesta final en español combinando todos los datos descubiertos\" }.";

                String aiResponse = userChatModel.generate(prompt);

                if (aiResponse.contains("{")) {
                    aiResponse = aiResponse.substring(aiResponse.indexOf("{"), aiResponse.lastIndexOf("}") + 1);
                }

                Map<String, Object> decision = mapper.readValue(aiResponse, Map.class);

                if ("tool".equals(decision.get("action"))) {
                    String name = (String) decision.get("toolName");
                    Map<String, Object> args = (Map<String, Object>) decision.get("arguments");

                    Object toolRes = executeTool(Map.of("url", toolMap.get(name), "toolName", name, "arguments", args));

                    conversationHistory += "\nSistema: Resultado de usar la herramienta '" + name + "' = " + mapper.writeValueAsString(toolRes);

                    executedTools.add(Map.of(
                            "name", name,
                            "arguments", args,
                            "rawResult", toolRes
                    ));
                } else {
                    return Map.of(
                            "reply", (String) decision.get("message"),
                            "toolInfo", executedTools
                    );
                }
            }

            return Map.of(
                    "reply", "He recopilado mucha información, pero alcancé el límite de seguridad de 5 herramientas seguidas.",
                    "toolInfo", executedTools
            );

        } catch (Exception e) {
            return Map.of("error", "Fallo interno en el orquestador: " + e.getMessage());
        }
    }
}