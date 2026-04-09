package org.acme.mcp;

import org.acme.mcp.service.McpServerConfigService;
import org.acme.mcp.service.McpServerProbeService;
import org.acme.mcp.service.WorkspaceChatProxyService;
import org.acme.mcp.service.WorkspaceProvisionService;
import org.acme.mcp.service.WorkspaceRuntimeLauncherService;
import org.acme.mcp.service.WorkspaceService;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SERVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void connectProbesAndSavesServer() throws Exception {
        TestMcpServerConfigService serverConfigService = new TestMcpServerConfigService();
        TestWorkspaceProvisionService provisionService = new TestWorkspaceProvisionService();
        TestMcpServerProbeService probeService = new TestMcpServerProbeService(true);

        McpController controller = controller(serverConfigService, probeService, new TestWorkspaceService(), provisionService, new TestWorkspaceRuntimeLauncherService(), new TestWorkspaceChatProxyService());
        Object response = controller.saveServer(Map.of("name", "Weather", "url", "http://fake.test/mcp"));
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertEquals("Weather", body.get("name"));
        assertEquals(true, body.get("saved"));
        assertTrue(body.containsKey("workspacePreview"));
        assertEquals(List.of(Map.of("userId", USER_ID, "name", "Weather", "url", "http://fake.test/mcp")), serverConfigService.saved);
    }

    @Test
    void connectRejectsUnreachableServer() throws Exception {
        McpController controller = controller(new TestMcpServerConfigService(), new TestMcpServerProbeService(false), new TestWorkspaceService(), new TestWorkspaceProvisionService(), new TestWorkspaceRuntimeLauncherService(), new TestWorkspaceChatProxyService());
        Object response = controller.saveServer(Map.of("name", "Weather", "url", "http://down.test/mcp"));
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertTrue(String.valueOf(body.get("error")).contains("No se pudo alcanzar"));
    }

    @Test
    void deleteRemovesServer() throws Exception {
        TestMcpServerConfigService serverConfigService = new TestMcpServerConfigService();
        McpController controller = controller(serverConfigService, new TestMcpServerProbeService(true), new TestWorkspaceService(), new TestWorkspaceProvisionService(), new TestWorkspaceRuntimeLauncherService(), new TestWorkspaceChatProxyService());

        Object response = controller.deleteServer(SERVER_ID.toString());
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertEquals(true, body.get("deleted"));
        assertEquals(SERVER_ID, serverConfigService.deletedServerId);
    }

    @Test
    void applyReturnsWorkspaceCommand() throws Exception {
        TestWorkspaceProvisionService provisionService = new TestWorkspaceProvisionService();
        McpController controller = controller(new TestMcpServerConfigService(), new TestMcpServerProbeService(true), new TestWorkspaceService(), provisionService, new TestWorkspaceRuntimeLauncherService(), new TestWorkspaceChatProxyService());

        Object response = controller.applyWorkspace();
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertEquals("provisioned", body.get("status"));
        assertTrue(String.valueOf(body.get("startupCommand")).contains("workspace-runtime"));
        assertEquals(USER_ID, provisionService.appliedUserId);
        assertTrue(body.containsKey("runtime"));
    }

    @Test
    void startDelegatesToRuntimeLauncher() throws Exception {
        TestWorkspaceRuntimeLauncherService launcherService = new TestWorkspaceRuntimeLauncherService();
        McpController controller = controller(new TestMcpServerConfigService(), new TestMcpServerProbeService(true), new TestWorkspaceService(), new TestWorkspaceProvisionService(), launcherService, new TestWorkspaceChatProxyService());

        Object response = controller.startWorkspaceRuntime();
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertEquals(true, body.get("started"));
        assertEquals(USER_ID, launcherService.startedUserId);
    }

    @Test
    void readsRuntimeLogs() throws Exception {
        TestWorkspaceRuntimeLauncherService launcherService = new TestWorkspaceRuntimeLauncherService();
        McpController controller = controller(new TestMcpServerConfigService(), new TestMcpServerProbeService(true), new TestWorkspaceService(), new TestWorkspaceProvisionService(), launcherService, new TestWorkspaceChatProxyService());

        Object response = controller.runtimeLogs();
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertTrue(String.valueOf(body.get("content")).contains("toolExecutionRequested"));
    }

    @Test
    void chatDelegatesToWorkspaceProxy() throws Exception {
        TestWorkspaceChatProxyService chatProxyService = new TestWorkspaceChatProxyService();
        McpController controller = controller(new TestMcpServerConfigService(), new TestMcpServerProbeService(true), new TestWorkspaceService(), new TestWorkspaceProvisionService(), new TestWorkspaceRuntimeLauncherService(), chatProxyService);

        Object response = controller.chatWithAgent(Map.of("message", "hola"));
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertEquals("respuesta runtime", body.get("reply"));
        assertEquals(USER_ID, chatProxyService.userId);
        assertEquals("hola", chatProxyService.message);
    }

    private static McpController controller(TestMcpServerConfigService serverConfigService,
                                            TestMcpServerProbeService probeService,
                                            TestWorkspaceService workspaceService,
                                            TestWorkspaceProvisionService provisionService,
                                            TestWorkspaceRuntimeLauncherService launcherService,
                                            TestWorkspaceChatProxyService chatProxyService) throws Exception {
        McpController controller = new McpController();
        setField(controller, "jwt", token(USER_ID));
        setField(controller, "serverConfigService", serverConfigService);
        setField(controller, "probeService", probeService);
        setField(controller, "workspaceService", workspaceService);
        setField(controller, "workspaceProvisionService", provisionService);
        setField(controller, "workspaceRuntimeLauncherService", launcherService);
        setField(controller, "workspaceChatProxyService", chatProxyService);
        return controller;
    }

    private static JsonWebToken token(UUID userId) {
        return (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(),
                new Class[]{JsonWebToken.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSubject", "getName" -> userId.toString();
                    case "getClaimNames" -> Set.of("sub");
                    case "containsClaim" -> "sub".equals(args[0]);
                    case "getClaim" -> "sub".equals(args[0]) ? userId.toString() : null;
                    default -> {
                        Class<?> returnType = method.getReturnType();
                        if (returnType.equals(boolean.class)) yield false;
                        if (returnType.equals(Set.class)) yield Set.of();
                        yield null;
                    }
                }
        );
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    static final class TestMcpServerConfigService extends McpServerConfigService {
        private final List<Map<String, Object>> saved = new ArrayList<>();
        private UUID deletedServerId;

        @Override
        public org.acme.mcp.model.McpServerConfig save(UUID userId, String name, String url) {
            saved.add(Map.of("userId", userId, "name", name, "url", url));
            return null;
        }

        @Override
        public void delete(UUID userId, UUID serverId) {
            deletedServerId = serverId;
        }

        @Override
        public List<Map<String, Object>> list(UUID userId) {
            return saved;
        }
    }

    static final class TestMcpServerProbeService extends McpServerProbeService {
        private final boolean ok;

        TestMcpServerProbeService(boolean ok) {
            this.ok = ok;
        }

        @Override
        public Map<String, Object> probe(String rawUrl) {
            if (ok) {
                return Map.of("ok", true, "status", 200, "message", "Servidor accesible. Codigo HTTP detectado: 200");
            }
            return Map.of("ok", false, "message", "No se pudo alcanzar el servidor MCP desde el backend: timeout");
        }
    }

    static final class TestWorkspaceService extends WorkspaceService {
        @Override
        public Map<String, Object> currentWorkspace(UUID userId) {
            return Map.of("id", USER_ID, "status", "draft");
        }
    }

    static final class TestWorkspaceProvisionService extends WorkspaceProvisionService {
        private UUID appliedUserId;

        @Override
        public Map<String, Object> preview(UUID userId) {
            return Map.of("content", "preview=true", "serverCount", 1, "runtimePort", 8090);
        }

        @Override
        public Map<String, Object> apply(UUID userId) {
            appliedUserId = userId;
            return Map.of(
                    "id", USER_ID,
                    "status", "provisioned",
                    "runtimePort", 8090,
                    "runtimeUrl", "http://localhost:8090",
                    "configPath", "C:/tmp/workspace-runtime.properties",
                    "startupCommand", ".\\mvnw.cmd -pl workspace-runtime quarkus:dev",
                    "content", "config=true",
                    "serverCount", 1
            );
        }
    }

    static final class TestWorkspaceRuntimeLauncherService extends WorkspaceRuntimeLauncherService {
        private UUID startedUserId;

        @Override
        public Map<String, Object> start(UUID userId) {
            startedUserId = userId;
            return Map.of("started", true, "runtimeUrl", "http://localhost:8090", "status", "starting");
        }

        @Override
        public Map<String, Object> stop(UUID userId) {
            return Map.of("stopped", true);
        }

        @Override
        public Map<String, Object> status(UUID userId) {
            return Map.of("running", false, "runtimeUrl", "http://localhost:8090", "workspaceStatus", "draft");
        }

        @Override
        public Map<String, Object> readLogTail(UUID userId) {
            return Map.of("content", "toolExecutionRequested weather.getCurrentWeather", "logPath", "C:/tmp/workspace-runtime.log", "runtimeRunning", true);
        }
    }

    static final class TestWorkspaceChatProxyService extends WorkspaceChatProxyService {
        private UUID userId;
        private String message;

        @Override
        public Map<String, Object> chat(UUID userId, String message) {
            this.userId = userId;
            this.message = message;
            return Map.of("reply", "respuesta runtime", "mode", "workspace-runtime");
        }
    }
}
