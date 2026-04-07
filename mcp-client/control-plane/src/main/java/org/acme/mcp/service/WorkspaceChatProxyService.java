package org.acme.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.mcp.model.Workspace;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class WorkspaceChatProxyService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    WorkspaceService workspaceService;

    @Inject
    ObjectMapper objectMapper;

    public Map<String, Object> chat(UUID userId, String message) {
        Workspace workspace = workspaceService.getOrCreateDefault(userId);
        if (!WorkspaceService.STATUS_PROVISIONED.equals(workspace.status) || workspace.runtimeUrl == null || workspace.runtimeUrl.isBlank()) {
            return Map.of("error", "Aplica primero la configuracion del workspace y arranca workspace-runtime.");
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "conversationId", userId.toString(),
                    "message", message
            ));
            HttpRequest request = HttpRequest.newBuilder(runtimeUri(workspace.runtimeUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> responseBody = response.body() == null || response.body().isBlank()
                    ? Map.of()
                    : objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            if (response.statusCode() >= 400) {
                String error = String.valueOf(responseBody.getOrDefault("error", "El workspace runtime devolvio un error HTTP " + response.statusCode() + '.'));
                return Map.of("error", error);
            }

            Map<String, Object> data = new LinkedHashMap<>(responseBody);
            data.putIfAbsent("mode", "workspace-runtime");
            data.put("runtimeUrl", workspace.runtimeUrl);
            return data;
        } catch (Exception e) {
            return Map.of(
                    "error", "No se pudo contactar con tu workspace runtime en " + workspace.runtimeUrl + ". Arrancalo con el comando generado en la configuracion. Detalle: " + e.getMessage()
            );
        }
    }

    private URI runtimeUri(String runtimeUrl) {
        String baseUrl = runtimeUrl.endsWith("/") ? runtimeUrl.substring(0, runtimeUrl.length() - 1) : runtimeUrl;
        return URI.create(baseUrl + "/api/chat");
    }
}
