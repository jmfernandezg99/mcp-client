package org.acme.mcp;

import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.acme.mcp.service.McpServerConfigService;
import org.acme.mcp.service.McpServerProbeService;
import org.acme.mcp.service.WorkspaceChatProxyService;
import org.acme.mcp.service.WorkspaceProvisionService;
import org.acme.mcp.service.WorkspaceRuntimeLauncherService;
import org.acme.mcp.service.WorkspaceService;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Path("/api/mcp")
public class McpController {

    @Inject
    JsonWebToken jwt;

    @Inject
    McpServerConfigService serverConfigService;

    @Inject
    McpServerProbeService probeService;

    @Inject
    WorkspaceService workspaceService;

    @Inject
    WorkspaceProvisionService workspaceProvisionService;

    @Inject
    WorkspaceRuntimeLauncherService workspaceRuntimeLauncherService;

    @Inject
    WorkspaceChatProxyService workspaceChatProxyService;

    @GET
    @Path("/servers")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Object listServers() {
        return serverConfigService.list(currentUserId());
    }

    @GET
    @Path("/workspace")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Object currentWorkspace() {
        return enrichWorkspace(workspaceService.currentWorkspace(currentUserId()));
    }

    @GET
    @Path("/workspace/config-preview")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Object previewWorkspaceConfig() {
        return enrichWorkspace(workspaceProvisionService.preview(currentUserId()));
    }

    @GET
    @Path("/workspace/runtime")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Object runtimeStatus() {
        return workspaceRuntimeLauncherService.status(currentUserId());
    }

    @GET
    @Path("/workspace/runtime/logs")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Object runtimeLogs() {
        return workspaceRuntimeLauncherService.readLogTail(currentUserId());
    }

    @POST
    @Path("/workspace/apply")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Object applyWorkspace() {
        UUID userId = currentUserId();
        try {
            return enrichWorkspace(workspaceProvisionService.apply(userId));
        } catch (Exception e) {
            workspaceService.markFailed(userId, e.getMessage());
            return Map.of("error", "No se pudo aplicar la configuracion del workspace: " + e.getMessage());
        }
    }

    @POST
    @Path("/workspace/start")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Object startWorkspaceRuntime() {
        return workspaceRuntimeLauncherService.start(currentUserId());
    }

    @POST
    @Path("/workspace/stop")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Object stopWorkspaceRuntime() {
        return workspaceRuntimeLauncherService.stop(currentUserId());
    }

    @POST
    @Path("/connect")
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object saveServer(Map<String, Object> payload) {
        UUID userId = currentUserId();
        String serverName = readString(payload, "name");
        String serverUrl = readString(payload, "url");
        if (serverName == null || serverName.isBlank()) {
            return Map.of("error", "El nombre del servidor MCP es obligatorio.");
        }
        if (serverUrl == null || serverUrl.isBlank()) {
            return Map.of("error", "La URL del servidor MCP es obligatoria.");
        }

        String normalizedName = serverName.trim();
        String normalizedUrl = serverUrl.trim();
        Map<String, Object> probe = probeService.probe(normalizedUrl);
        if (!Boolean.TRUE.equals(probe.get("ok"))) {
            return Map.of("error", probe.get("message"));
        }

        try {
            serverConfigService.save(userId, normalizedName, normalizedUrl);
            return Map.of(
                    "name", normalizedName,
                    "url", normalizedUrl,
                    "saved", true,
                    "probe", probe,
                    "message", "Servidor validado y guardado en tu workspace. Cuando acabes, aplica la configuracion para generar o actualizar tu runtime declarativo.",
                    "workspacePreview", enrichWorkspace(workspaceProvisionService.preview(userId))
            );
        } catch (Exception e) {
            return Map.of("error", "Error guardando la configuracion MCP: " + e.getMessage());
        }
    }

    @DELETE
    @Path("/servers/{serverId}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Object deleteServer(@PathParam("serverId") String serverId) {
        try {
            UUID userId = currentUserId();
            serverConfigService.delete(userId, UUID.fromString(serverId));
            return Map.of(
                    "deleted", true,
                    "workspacePreview", enrichWorkspace(workspaceProvisionService.preview(userId))
            );
        } catch (Exception e) {
            return Map.of("error", "No se pudo eliminar el servidor MCP: " + e.getMessage());
        }
    }

    @POST
    @Path("/execute")
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object executeTool(Map<String, Object> payload) {
        return Map.of(
                "error", "La ejecucion directa de tools no esta disponible desde control-plane. Usa el chat del workspace runtime una vez aplicado y arrancado."
        );
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
        return workspaceChatProxyService.chat(currentUserId(), userMessage);
    }

    private Map<String, Object> enrichWorkspace(Map<String, Object> workspaceData) {
        Map<String, Object> data = new LinkedHashMap<>(workspaceData);
        data.put("runtime", workspaceRuntimeLauncherService.status(currentUserId()));
        return data;
    }

    private UUID currentUserId() {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalStateException("No hay usuario autenticado.");
        }
        return UUID.fromString(jwt.getSubject());
    }

    private String readString(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
