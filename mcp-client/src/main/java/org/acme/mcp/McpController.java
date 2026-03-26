package org.acme.mcp;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.acme.mcp.model.JsonRpcRequest;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.smallrye.common.annotation.Blocking;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.annotation.PostConstruct;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Path("/api/mcp")
public class McpController {

    @ConfigProperty(name = "gemini.api.key")
    String apiKey;

    private ChatLanguageModel chatModel;

    @PostConstruct
    void initIA() {
        try {
            this.chatModel = GoogleAiGeminiChatModel.builder()
                    .apiKey(this.apiKey.trim())
                    .modelName("gemini-2.5-flash")
                    .temperature(0.0)
                    .build();
            System.out.println("✅ Cerebro Gemini 2.0 Flash activado.");
        } catch (Exception e) {
            System.err.println("❌ Error IA: " + e.getMessage());
        }
    }

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
        if (activeSessions.containsKey(targetUrl)) return Map.of("tools", activeSessions.get(targetUrl).cachedTools);

        try {
            McpWeatherClient sseClient = QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(targetUrl)).build(McpWeatherClient.class);
            ActiveSession newSession = new ActiveSession();
            AtomicReference<String> sessionUrl = new AtomicReference<>();
            CountDownLatch endpointLatch = new CountDownLatch(1);

            sseClient.connectToSse().subscribe().with(event -> {
                if ("endpoint".equals(event.name())) {
                    sessionUrl.set(event.data());
                    endpointLatch.countDown();
                } else if ("message".equals(event.name())) {
                    newSession.lastMessage.set(event.data());
                    newSession.messageLatch.get().countDown();
                }
            }, f -> {});

            if (!endpointLatch.await(5, TimeUnit.SECONDS)) return Map.of("error", "Timeout SSE.");

            newSession.messageClient = QuarkusRestClientBuilder.newBuilder().baseUri(new URI(targetUrl + sessionUrl.get())).build(McpWeatherClient.class);
            activeSessions.put(targetUrl, newSession);

            newSession.messageLatch.set(new CountDownLatch(1));
            newSession.messageClient.sendMessage(new JsonRpcRequest(1, "initialize", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "QuarkusClient", "version", "1.0"))));
            newSession.messageLatch.get().await(5, TimeUnit.SECONDS);

            return askForTools(targetUrl);
        } catch (Exception e) { return Map.of("error", e.getMessage()); }
    }

    private Object askForTools(String targetUrl) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            ActiveSession session = activeSessions.get(targetUrl);
            session.messageLatch.set(new CountDownLatch(1));
            session.messageClient.sendMessage(new JsonRpcRequest(3, "tools/list", Map.of()));
            session.messageLatch.get().await(5, TimeUnit.SECONDS);

            String raw = session.lastMessage.get();
            Map<String, Object> fullRes = mapper.readValue(raw, Map.class);
            Map<String, Object> result = (Map<String, Object>) fullRes.get("result");
            if (result != null && result.containsKey("tools")) {
                session.cachedTools = (List<Map<String, Object>>) result.get("tools");
            }
            return raw;
        } catch (Exception e) { return Map.of("error", e.getMessage()); }
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
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object chatWithAgent(Map<String, Object> payload) {
        String userMessage = (String) payload.get("message");
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

            String prompt = "Responde SOLO JSON. Usuario: " + userMessage + ". Herramientas: " + mapper.writeValueAsString(allTools) +
                    ". Formato: {\"action\": \"tool\", \"toolName\": \"...\", \"arguments\": {...}} o {\"action\": \"reply\", \"message\": \"...\"}";

            String aiResponse = chatModel.generate(prompt);
            if (aiResponse.contains("{")) {
                aiResponse = aiResponse.substring(aiResponse.indexOf("{"), aiResponse.lastIndexOf("}") + 1);
            }

            Map<String, Object> decision = mapper.readValue(aiResponse, Map.class);

            if ("tool".equals(decision.get("action"))) {
                String name = (String) decision.get("toolName");
                Map<String, Object> args = (Map<String, Object>) decision.get("arguments");

                Object toolRes = executeTool(Map.of("url", toolMap.get(name), "toolName", name, "arguments", args));

                String finalAns = chatModel.generate("Resultado de " + name + ": " + mapper.writeValueAsString(toolRes) + ". Responde al usuario en español.");

                return Map.of(
                        "reply", finalAns,
                        "toolInfo", Map.of(
                                "name", name,
                                "arguments", args,
                                "rawResult", toolRes
                        )
                );
            }
            return Map.of("reply", (String) decision.get("message"));
        } catch (Exception e) { return Map.of("error", "Fallo: " + e.getMessage()); }
    }
}