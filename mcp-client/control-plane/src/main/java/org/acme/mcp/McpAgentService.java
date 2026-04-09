package org.acme.mcp;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class McpAgentService {

    @Inject
    Instance<DeclarativeMcpAssistant> declarativeAssistant;

    public Object runAssistant(UUID userId, ChatModel ignoredChatModel, String userMessage) {
        if (declarativeAssistant.isUnsatisfied()) {
            return Map.of("error", "No hay un AI service declarativo disponible. Reinicia la aplicacion con la configuracion MCP exportada y una GEMINI_API_KEY global.");
        }
        try {
            String reply = declarativeAssistant.get().chat(userId.toString(), userMessage);
            if (reply == null || reply.isBlank()) {
                reply = "El modelo declarativo no devolvio una respuesta final.";
            }
            return Map.of("reply", reply, "toolInfo", List.of(), "mode", "declarative");
        } catch (Exception e) {
            return Map.of("error", "Fallo en el modo declarativo: " + e.getMessage());
        }
    }

    public boolean usesDeclarativeMode() {
        return true;
    }
}