package org.acme.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class McpAgentService {

    private static final String SYSTEM_PROMPT = "Eres un asistente que puede usar herramientas MCP para obtener datos externos. "
            + "Usa las herramientas cuando necesites informacion del mundo real y responde en espanol de forma concisa.";
    private static final int MAX_AGENT_STEPS = 8;

    interface McpAssistant {
        String chat(String message);
    }

    @Inject
    ObjectMapper mapper;

    @Inject
    McpSchemaMapper schemaMapper;

    public Object runAssistant(ChatModel chatModel, String userMessage, List<McpSession> connectedSessions) {
        Map<String, Integer> toolOccurrences = schemaMapper.collectToolOccurrences(connectedSessions);
        McpToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(connectedSessions.stream().map(McpSession::client).toArray(McpClient[]::new))
                .failIfOneServerFails(false)
                .toolNameMapper((client, tool) -> schemaMapper.exposedToolName(client.key(), tool.name(), toolOccurrences))
                .build();

        List<Map<String, Object>> executedTools = new ArrayList<>();
        try {
            McpAssistant assistant = AiServices.builder(McpAssistant.class)
                    .chatModel(chatModel)
                    .systemMessage(SYSTEM_PROMPT)
                    .toolProvider(toolProvider)
                    .maxSequentialToolsInvocations(MAX_AGENT_STEPS)
                    .afterToolExecution(toolExecution -> executedTools.add(toToolTrace(toolExecution)))
                    .build();

            String reply = assistant.chat(userMessage);
            if (reply == null || reply.isBlank()) {
                reply = executedTools.isEmpty()
                        ? "El modelo no devolvio una respuesta final."
                        : "He ejecutado herramientas, pero el modelo no devolvio una respuesta final valida.";
            }
            return Map.of("reply", reply, "toolInfo", executedTools);
        } catch (Exception e) {
            return Map.of("error", "Fallo interno en el orquestador: " + e.getMessage());
        }
    }

    Map<String, Object> toToolTrace(ToolExecution toolExecution) {
        try {
            return Map.of(
                    "name", toolExecution.request().name(),
                    "arguments", parseArguments(toolExecution.request().arguments()),
                    "rawResult", toolExecution.resultObject() == null ? toolExecution.result() : toolExecution.resultObject()
            );
        } catch (Exception e) {
            return Map.of(
                    "name", toolExecution.request().name(),
                    "arguments", Map.of(),
                    "rawResult", toolExecution.result()
            );
        }
    }

    Map<String, Object> parseArguments(String argumentsJson) throws Exception {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(argumentsJson, Map.class);
    }
}
