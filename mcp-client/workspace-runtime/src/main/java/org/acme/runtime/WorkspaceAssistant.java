package org.acme.runtime;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;

@RegisterAiService
public interface WorkspaceAssistant {

    @SystemMessage("Eres un asistente que puede usar herramientas MCP para obtener datos externos. Usa las herramientas cuando necesites informacion del mundo real y responde en espanol de forma concisa.")
    @McpToolBox
    String chat(@MemoryId String conversationId, @UserMessage String message);
}
