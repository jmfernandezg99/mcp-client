package org.acme.runtime;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/api/chat")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChatResource {

    @Inject
    WorkspaceAssistant assistant;

    @POST
    public Map<String, Object> chat(Map<String, Object> payload) {
        String conversationId = payload == null || payload.get("conversationId") == null
                ? "default"
                : String.valueOf(payload.get("conversationId"));
        String message = payload == null || payload.get("message") == null
                ? ""
                : String.valueOf(payload.get("message"));
        if (message.isBlank()) {
            return Map.of("error", "El mensaje es obligatorio.");
        }
        return Map.of("reply", assistant.chat(conversationId, message));
    }
}
