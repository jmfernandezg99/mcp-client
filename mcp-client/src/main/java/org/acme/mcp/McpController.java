package org.acme.mcp;

import org.acme.mcp.model.JsonRpcRequest;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.smallrye.common.annotation.Blocking;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Path("/api/mcp")
public class McpController {

    // 🌟 EL MAPA MÁGICO: Guarda las conexiones de todos los servidores al mismo tiempo.
    // Clave: URL del servidor | Valor: La sesión activa (Túnel)
    private static final Map<String, ActiveSession> activeSessions = new ConcurrentHashMap<>();

    // Estructura para guardar el walkie-talkie de cada servidor de forma independiente
    public static class ActiveSession {
        public McpWeatherClient messageClient;
        public AtomicReference<String> lastMessage = new AtomicReference<>();
        public AtomicReference<CountDownLatch> messageLatch = new AtomicReference<>(new CountDownLatch(1));
    }

    @POST
    @Path("/connect")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object connectToServer(Map<String, Object> payload) {
        String targetUrl = (String) payload.get("url");
        if (targetUrl == null || targetUrl.isEmpty()) {
            return Map.of("error", "URL inválida.");
        }

        // Si ya estamos conectados a este servidor, no repetimos el saludo, solo pedimos las herramientas
        if (activeSessions.containsKey(targetUrl)) {
            System.out.println(">>> [INFO] Ya estábamos conectados a " + targetUrl);
            return askForTools(targetUrl);
        }

        try {
            System.out.println(">>> [NUEVO SERVIDOR] Abriendo conexión persistente con: " + targetUrl);

            McpWeatherClient sseClient = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(targetUrl)).build(McpWeatherClient.class);

            ActiveSession newSession = new ActiveSession();
            AtomicReference<String> sessionUrl = new AtomicReference<>();
            CountDownLatch endpointLatch = new CountDownLatch(1);

            // Dejamos el túnel abierto escuchando en segundo plano para SIEMPRE
            sseClient.connectToSse().subscribe().with(
                    event -> {
                        if ("endpoint".equals(event.name())) {
                            sessionUrl.set(event.data());
                            endpointLatch.countDown();
                        } else if ("message".equals(event.name())) {
                            newSession.lastMessage.set(event.data());
                            newSession.messageLatch.get().countDown(); // Avisamos de que llegó un paquete
                        }
                    },
                    failure -> System.out.println(">>> ERROR SSE (" + targetUrl + "): " + failure.getMessage())
            );

            if (!endpointLatch.await(5, TimeUnit.SECONDS)) return Map.of("error", "Timeout SSE.");

            URI fullEndpointUri = new URI(targetUrl + sessionUrl.get());
            newSession.messageClient = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(fullEndpointUri).build(McpWeatherClient.class);

            // Guardamos el servidor en el diccionario ANTES de mandar mensajes
            activeSessions.put(targetUrl, newSession);

            // Handshake de bienvenida
            JsonRpcRequest initRequest = new JsonRpcRequest(1, "initialize",
                    Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "UniversalClient", "version", "1.0"))
            );

            newSession.messageLatch.set(new CountDownLatch(1));
            newSession.messageClient.sendMessage(initRequest);
            newSession.messageLatch.get().await(5, TimeUnit.SECONDS);

            // Descubrimos sus herramientas
            return askForTools(targetUrl);

        } catch (Exception e) {
            return Map.of("error", "Fallo de conexión: " + e.getMessage());
        }
    }

    private Object askForTools(String targetUrl) {
        try {
            ActiveSession session = activeSessions.get(targetUrl);
            session.messageLatch.set(new CountDownLatch(1));
            session.messageClient.sendMessage(new JsonRpcRequest(3, "tools/list", Map.of()));

            if (!session.messageLatch.get().await(5, TimeUnit.SECONDS)) return Map.of("error", "Timeout herramientas.");
            return session.lastMessage.get();
        } catch (Exception e) {
            return Map.of("error", "Error listando: " + e.getMessage());
        }
    }

    @POST
    @Path("/execute")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object executeTool(Map<String, Object> payload) {
        String targetUrl = (String) payload.get("url");
        String toolName = (String) payload.get("toolName");
        Map<String, Object> arguments = (Map<String, Object>) payload.get("arguments");

        // 🌟 Buscamos el túnel correcto en nuestro diccionario
        ActiveSession session = activeSessions.get(targetUrl);
        if (session == null) {
            return Map.of("error", "No estás conectado a este servidor.");
        }

        try {
            session.messageLatch.set(new CountDownLatch(1)); // Preparamos la oreja
            JsonRpcRequest executeRequest = new JsonRpcRequest(4, "tools/call",
                    Map.of("name", toolName, "arguments", arguments != null ? arguments : Map.of())
            );

            // Disparamos por el túnel específico
            session.messageClient.sendMessage(executeRequest);

            if (!session.messageLatch.get().await(10, TimeUnit.SECONDS)) {
                return Map.of("error", "El servidor tardó mucho en responder.");
            }

            return session.lastMessage.get();
        } catch (Exception e) {
            return Map.of("error", "Fallo al ejecutar: " + e.getMessage());
        }
    }
}