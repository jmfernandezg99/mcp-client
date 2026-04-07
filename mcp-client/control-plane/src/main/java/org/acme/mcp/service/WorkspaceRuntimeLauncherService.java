package org.acme.mcp.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.mcp.model.User;
import org.acme.mcp.model.Workspace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class WorkspaceRuntimeLauncherService {

    private final Map<UUID, Process> processes = new ConcurrentHashMap<>();

    @Inject
    WorkspaceService workspaceService;

    @Inject
    EncryptionService encryptionService;

    public Map<String, Object> start(UUID userId) {
        Workspace workspace = workspaceService.getOrCreateDefault(userId);
        if (!WorkspaceService.STATUS_PROVISIONED.equals(workspace.status) || workspace.configPath == null || workspace.configPath.isBlank()) {
            return Map.of("error", "Primero debes aplicar la configuracion del workspace.");
        }

        Process existing = processes.get(userId);
        if (existing != null && existing.isAlive()) {
            return status(userId);
        }

        User user = User.findById(userId);
        if (user == null || user.geminiKeyEnc == null || user.geminiKeyEnc.isBlank()) {
            return Map.of("error", "El usuario no tiene una API key de Gemini disponible.");
        }

        String geminiKey = encryptionService.decrypt(user.geminiKeyEnc);
        Path repoRoot = repoRoot();
        Path configPath = Paths.get(workspace.configPath).toAbsolutePath();
        Path logPath = configPath.getParent().resolve("workspace-runtime.log");
        try {
            Files.createDirectories(logPath.getParent());
        } catch (IOException e) {
            return Map.of("error", "No se pudo preparar el directorio de logs del workspace: " + e.getMessage());
        }

        ProcessBuilder builder = new ProcessBuilder(
                "cmd.exe", "/c",
                "mvnw.cmd", "-pl", "workspace-runtime", "quarkus:dev",
                "-Dquarkus.http.port=" + workspace.runtimePort,
                "-Dquarkus.config.locations=" + configPath.toUri()
        );
        builder.directory(repoRoot.toFile());
        builder.environment().put("GEMINI_API_KEY", geminiKey);
        builder.redirectErrorStream(true);
        builder.redirectOutput(logPath.toFile());

        try {
            Process process = builder.start();
            processes.put(userId, process);
            return Map.of(
                    "started", true,
                    "runtimeUrl", workspace.runtimeUrl,
                    "logPath", logPath.toAbsolutePath().toString(),
                    "status", "starting"
            );
        } catch (IOException e) {
            return Map.of("error", "No se pudo arrancar workspace-runtime: " + e.getMessage());
        }
    }

    public Map<String, Object> stop(UUID userId) {
        Process process = processes.remove(userId);
        if (process == null) {
            return Map.of("stopped", false, "message", "No habia ningun workspace-runtime arrancado para este usuario.");
        }
        process.destroy();
        return Map.of("stopped", true);
    }

    public Map<String, Object> status(UUID userId) {
        Workspace workspace = workspaceService.getOrCreateDefault(userId);
        Process process = processes.get(userId);
        boolean running = process != null && process.isAlive();
        if (!running && process != null) {
            processes.remove(userId);
        }
        String logPath = workspace.configPath == null || workspace.configPath.isBlank()
                ? ""
                : Paths.get(workspace.configPath).getParent().resolve("workspace-runtime.log").toAbsolutePath().toString();
        return Map.of(
                "running", running,
                "runtimeUrl", workspace.runtimeUrl == null ? "" : workspace.runtimeUrl,
                "logPath", logPath,
                "workspaceStatus", workspace.status
        );
    }

    private Path repoRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("mvnw.cmd")) && Files.isDirectory(current.resolve("workspace-runtime"))) {
                return current;
            }
            current = current.getParent();
        }
        return new File(".").toPath().toAbsolutePath();
    }
}
