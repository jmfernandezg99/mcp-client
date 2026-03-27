package org.acme.mcp.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.mcp.model.User;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class UserModelCache {

    @Inject
    EncryptionService encryptionService;

    // Cache thread-safe models por usuario
    private final ConcurrentHashMap<UUID, ChatLanguageModel> cache = new ConcurrentHashMap<>();

    public ChatLanguageModel getModel(UUID userId) {
        return cache.computeIfAbsent(userId, id -> {
            User user = User.findById(id);
            if (user == null) {
                throw new IllegalStateException("Usuario no encontrado.");
            }
            
            String geminiKey = encryptionService.decrypt(user.geminiKeyEnc);
            
            return GoogleAiGeminiChatModel.builder()
                    .apiKey(geminiKey)
                    .modelName("gemini-2.5-flash")
                    .temperature(0.0)
                    .build();
        });
    }

    public void invalidate(UUID userId) {
        cache.remove(userId);
    }
}
