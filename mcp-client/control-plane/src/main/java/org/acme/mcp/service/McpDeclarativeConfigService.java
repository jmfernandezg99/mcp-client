package org.acme.mcp.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.mcp.model.McpServerConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class McpDeclarativeConfigService {

    private static final Path GENERATED_DIR = Paths.get("generated");

    public Map<String, Object> exportPublishedProfile() {
        List<McpServerConfig> servers = McpServerConfig.listPublished();
        String properties = toProperties(servers);
        Path path = GENERATED_DIR.resolve("mcp-servers-active.properties");
        write(path, properties);
        return Map.of(
                "path", path.toAbsolutePath().toString(),
                "content", properties,
                "serverCount", servers.size(),
                "restartRequired", true
        );
    }

    public Map<String, Object> previewPublishedProfile() {
        List<McpServerConfig> servers = McpServerConfig.listPublished();
        return Map.of(
                "content", toProperties(servers),
                "serverCount", servers.size(),
                "restartRequired", true
        );
    }

    public Map<String, Object> previewForUser(UUID userId) {
        List<McpServerConfig> servers = McpServerConfig.listByUserId(userId);
        return Map.of(
                "content", toProperties(servers),
                "serverCount", servers.size(),
                "restartRequired", true
        );
    }

    public String toProperties(List<McpServerConfig> servers) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Generated from MCP servers\n\n");

        Map<String, Integer> usedNames = new LinkedHashMap<>();
        for (McpServerConfig server : servers) {
            String clientName = uniqueClientName(sanitize(server.name), usedNames);
            builder.append("quarkus.langchain4j.mcp.").append(clientName).append(".transport-type=streamable-http\n");
            builder.append("quarkus.langchain4j.mcp.").append(clientName).append(".url=").append(server.url).append("\n");
            builder.append("quarkus.langchain4j.mcp.").append(clientName).append(".log-requests=true\n");
            builder.append("quarkus.langchain4j.mcp.").append(clientName).append(".log-responses=true\n\n");
        }

        if (servers.isEmpty()) {
            builder.append("# No hay servidores MCP configurados para este workspace.\n");
        }
        return builder.toString();
    }

    private String uniqueClientName(String baseName, Map<String, Integer> usedNames) {
        int count = usedNames.merge(baseName, 1, Integer::sum);
        return count == 1 ? baseName : baseName + "_" + count;
    }

    private String sanitize(String raw) {
        String sanitized = raw == null ? "mcp_server" : raw.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return sanitized.isBlank() ? "mcp_server" : sanitized;
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el fichero declarativo de MCP.", e);
        }
    }
}
