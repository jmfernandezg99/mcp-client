package org.acme.mcp.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.acme.mcp.model.McpServerConfig;
import org.acme.mcp.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class McpServerConfigService {

    @Transactional
    public McpServerConfig save(UUID userId, String name, String url) {
        User user = User.findById(userId);
        if (user == null) {
            throw new IllegalStateException("Usuario no encontrado.");
        }

        McpServerConfig config = McpServerConfig.findByUserIdAndName(userId, name);
        if (config == null) {
            config = McpServerConfig.findByUserIdAndUrl(userId, url);
        }
        if (config == null) {
            config = new McpServerConfig();
            config.user = user;
            config.published = false;
        }

        config.name = name;
        config.url = url;
        config.persist();
        return config;
    }

    @Transactional
    public void delete(UUID userId, UUID serverId) {
        McpServerConfig config = McpServerConfig.findById(serverId);
        if (config == null || !config.user.id.equals(userId)) {
            throw new IllegalStateException("Servidor MCP no encontrado para este usuario.");
        }
        config.delete();
    }

    @Transactional
    public void publishUserProfile(UUID userId) {
        User user = User.findById(userId);
        if (user == null) {
            throw new IllegalStateException("Usuario no encontrado.");
        }

        LocalDateTime now = LocalDateTime.now();
        for (McpServerConfig config : McpServerConfig.<McpServerConfig>listAll()) {
            boolean shouldPublish = config.user.id.equals(userId);
            config.published = shouldPublish;
            config.publishedAt = shouldPublish ? now : null;
            config.persist();
        }
    }

    public List<Map<String, Object>> list(UUID userId) {
        return McpServerConfig.listByUserId(userId).stream()
                .map(this::toMap)
                .toList();
    }

    public List<Map<String, Object>> listPublished() {
        return McpServerConfig.listPublished().stream()
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> activePublishedProfile() {
        List<McpServerConfig> published = McpServerConfig.listPublished();
        if (published.isEmpty()) {
            return Map.of(
                    "published", false,
                    "servers", List.of()
            );
        }

        McpServerConfig first = published.get(0);
        return Map.of(
                "published", true,
                "userId", first.user.id,
                "username", first.user.username,
                "publishedAt", first.publishedAt,
                "servers", published.stream().map(this::toMap).toList()
        );
    }

    private Map<String, Object> toMap(McpServerConfig config) {
        return Map.of(
                "id", config.id,
                "name", config.name,
                "url", config.url,
                "published", config.published,
                "publishedAt", config.publishedAt == null ? "" : config.publishedAt.toString()
        );
    }
}
