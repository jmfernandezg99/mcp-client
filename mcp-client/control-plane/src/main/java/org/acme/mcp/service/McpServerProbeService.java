package org.acme.mcp.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@ApplicationScoped
public class McpServerProbeService {

    private static final String INITIALIZE_BODY = """
            {"jsonrpc":"2.0","id":"probe-initialize","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ClaudioProbe","version":"1.0.0"}}}
            """;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Map<String, Object> probe(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            return Map.of("ok", false, "message", "La URL no es valida.");
        }

        if (uri.getScheme() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            return Map.of("ok", false, "message", "La URL debe usar http o https.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return Map.of("ok", false, "message", "La URL debe incluir un host valido.");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_BODY))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (!isMcpInitializeResponse(response.statusCode(), response.body())) {
                return Map.of(
                        "ok", false,
                        "status", response.statusCode(),
                        "message", "La URL responde HTTP, pero no parece un endpoint MCP streamable-http valido."
                );
            }
            return Map.of(
                    "ok", true,
                    "status", response.statusCode(),
                    "message", "Endpoint MCP valido. Responde correctamente al initialize.")
                    ;
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "message", "No se pudo alcanzar o validar el servidor MCP desde el backend: " + e.getMessage()
            );
        }
    }

    private boolean isMcpInitializeResponse(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            return false;
        }
        if (body == null || body.isBlank()) {
            return false;
        }
        return body.contains("\"jsonrpc\"") && (body.contains("\"result\"") || body.contains("\"error\""));
    }
}
