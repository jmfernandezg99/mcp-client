package org.acme.mcp.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.acme.mcp.model.User;
import org.acme.mcp.model.Workspace;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class WorkspaceService {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PROVISIONED = "provisioned";
    public static final String STATUS_FAILED = "failed";

    @Transactional
    public Workspace getOrCreateDefault(UUID userId) {
        Workspace workspace = Workspace.findByUserId(userId);
        if (workspace != null) {
            return workspace;
        }

        User user = User.findById(userId);
        if (user == null) {
            throw new IllegalStateException("Usuario no encontrado.");
        }

        workspace = new Workspace();
        workspace.user = user;
        workspace.name = "default";
        workspace.status = STATUS_DRAFT;
        workspace.persist();
        return workspace;
    }

    public Map<String, Object> currentWorkspace(UUID userId) {
        return toMap(getOrCreateDefault(userId));
    }

    @Transactional
    public int ensureRuntimePort(UUID userId) {
        Workspace workspace = getOrCreateDefault(userId);
        if (workspace.runtimePort == null) {
            workspace.runtimePort = nextAvailablePort();
            workspace.persist();
        }
        return workspace.runtimePort;
    }

    @Transactional
    public Workspace markProvisioned(UUID userId, int runtimePort, String configPath, String startupCommand) {
        Workspace workspace = getOrCreateDefault(userId);
        workspace.runtimePort = runtimePort;
        workspace.runtimeUrl = "http://localhost:" + runtimePort;
        workspace.configPath = configPath;
        workspace.startupCommand = startupCommand;
        workspace.status = STATUS_PROVISIONED;
        workspace.lastError = null;
        workspace.lastAppliedAt = LocalDateTime.now();
        workspace.persist();
        return workspace;
    }

    @Transactional
    public Workspace markFailed(UUID userId, String errorMessage) {
        Workspace workspace = getOrCreateDefault(userId);
        workspace.status = STATUS_FAILED;
        workspace.lastError = errorMessage;
        workspace.persist();
        return workspace;
    }

    public Map<String, Object> toMap(Workspace workspace) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", workspace.id);
        data.put("name", workspace.name);
        data.put("status", workspace.status);
        data.put("runtimePort", workspace.runtimePort);
        data.put("runtimeUrl", workspace.runtimeUrl == null ? "" : workspace.runtimeUrl);
        data.put("configPath", workspace.configPath == null ? "" : workspace.configPath);
        data.put("startupCommand", workspace.startupCommand == null ? "" : workspace.startupCommand);
        data.put("lastError", workspace.lastError == null ? "" : workspace.lastError);
        data.put("lastAppliedAt", workspace.lastAppliedAt == null ? "" : workspace.lastAppliedAt.toString());
        data.put("createdAt", workspace.createdAt == null ? "" : workspace.createdAt.toString());
        return data;
    }

    private int nextAvailablePort() {
        int candidate = 8090;
        for (Integer assignedPort : Workspace.listAssignedPorts()) {
            if (assignedPort == null) {
                continue;
            }
            if (assignedPort > candidate) {
                break;
            }
            if (assignedPort == candidate) {
                candidate++;
            }
        }
        return candidate;
    }
}
