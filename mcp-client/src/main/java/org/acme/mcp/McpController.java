package org.acme.mcp;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.acme.mcp.service.UserModelCache;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/mcp")
public class McpController {

    @Inject
    JsonWebToken jwt;

    @Inject
    UserModelCache cache;

    @Inject
    McpSessionService sessionService;

    @Inject
    McpSchemaMapper schemaMapper;

    @Inject
    McpAgentService agentService;

    @POST
    @Path("/connect")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object connectToServer(Map<String, Object> payload) {
        String serverUrl = readString(payload, "url");
        if (serverUrl == null || serverUrl.isBlank()) {
            return Map.of("error", "La URL del servidor MCP es obligatoria.");
        }

        String normalizedUrl = serverUrl.trim();
        try {
            McpSession session = sessionService.getOrOpenSession(normalizedUrl);
            return Map.of(
                    "tools", schemaMapper.toApiTools(session, schemaMapper.collectToolOccurrences(sessionService.connectedSessions())),
                    "endpoint", session.endpointUrl()
            );
        } catch (Exception e) {
            sessionService.removeSession(normalizedUrl);
            return Map.of("error", "Error de conexion MCP: " + e.getMessage());
        }
    }

    @POST
    @Path("/execute")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object executeTool(Map<String, Object> payload) {
        if (payload == null) {
            return Map.of("error", "Payload vacio.");
        }

        String serverUrl = readString(payload, "url");
        String requestedToolName = readString(payload, "toolName");
        McpSession session = sessionService.getSession(serverUrl);
        if (session == null) {
            return Map.of("error", "No existe una sesion MCP activa para esa URL.");
        }
        if (requestedToolName == null || requestedToolName.isBlank()) {
            return Map.of("error", "El nombre de la herramienta es obligatorio.");
        }

        try {
            ToolExecutionResult result = sessionService.executeTool(session, requestedToolName, readMap(payload.get("arguments")));
            return Map.of(
                    "isError", result.isError(),
                    "result", result.result(),
                    "text", result.resultText()
            );
        } catch (Exception e) {
            return Map.of("error", "Error ejecutando la herramienta: " + e.getMessage());
        }
    }

    @POST
    @Path("/chat")
    @Blocking
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object chatWithAgent(Map<String, Object> payload) {
        String userMessage = readString(payload, "message");
        if (userMessage == null || userMessage.isBlank()) {
            return Map.of("error", "El mensaje es obligatorio.");
        }

        List<McpSession> connectedSessions = sessionService.connectedSessions();
        if (connectedSessions.isEmpty()) {
            return Map.of("reply", "No detecto herramientas conectadas.");
        }

        UUID userId = UUID.fromString(jwt.getSubject());
        ChatModel chatModel = cache.getModel(userId);
        return agentService.runAssistant(chatModel, userMessage, connectedSessions);
    }

    private Map<String, Object> readMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private String readString(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
