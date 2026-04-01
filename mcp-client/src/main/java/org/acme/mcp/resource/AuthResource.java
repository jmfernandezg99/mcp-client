package org.acme.mcp.resource;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.mcp.dto.AuthResponse;
import org.acme.mcp.dto.LoginRequest;
import org.acme.mcp.dto.RegisterRequest;
import org.acme.mcp.dto.UpdateKeyRequest;
import org.acme.mcp.model.User;
import org.acme.mcp.service.EncryptionService;
import org.acme.mcp.service.JwtService;
import org.acme.mcp.service.UserModelCache;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.mindrot.jbcrypt.BCrypt;

import java.util.Map;
import java.util.UUID;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    EncryptionService encryptionService;

    @Inject
    JwtService jwtService;

    @Inject
    UserModelCache cache;

    @Inject
    JsonWebToken jwt;

    @POST
    @Path("/register")
    @Transactional
    public Response register(RegisterRequest req) {
        if (req.username == null || req.password == null || req.geminiKey == null) {
            return Response.status(400).entity(AuthResponse.error("Datos incompletos.")).build();
        }

        if (User.findByUsername(req.username) != null) {
            return Response.status(400).entity(AuthResponse.error("Usuario ya existe.")).build();
        }

        User user = new User();
        user.username = req.username;
        user.passwordHash = BCrypt.hashpw(req.password, BCrypt.gensalt());
        user.geminiKeyEnc = encryptionService.encrypt(req.geminiKey);
        user.persist();

        String token = jwtService.generateToken(user.id, user.username);
        return Response.ok(AuthResponse.success(token)).build();
    }

    @POST
    @Path("/login")
    @Transactional
    public Response login(LoginRequest req) {
        if (req.username == null || req.password == null) {
            return Response.status(400).entity(AuthResponse.error("Datos incompletos.")).build();
        }

        User user = User.findByUsername(req.username);
        if (user == null || !BCrypt.checkpw(req.password, user.passwordHash)) {
            return Response.status(401).entity(AuthResponse.error("Credenciales invalidas.")).build();
        }

        String token = jwtService.generateToken(user.id, user.username);
        return Response.ok(AuthResponse.success(token)).build();
    }

    @PUT
    @Path("/keys")
    @Authenticated
    @Transactional
    public Response updateKey(UpdateKeyRequest req) {
        if (req.newGeminiKey == null || req.newGeminiKey.trim().isEmpty()) {
            return Response.status(400).entity(Map.of("error", "Key no valida")).build();
        }

        UUID userId = UUID.fromString(jwt.getSubject());
        User user = User.findById(userId);
        if (user == null) {
            return Response.status(404).entity(Map.of("error", "Usuario no encontrado")).build();
        }

        user.geminiKeyEnc = encryptionService.encrypt(req.newGeminiKey);
        user.persist();
        cache.invalidate(userId);

        return Response.ok(Map.of("message", "API key actualizada correctamente.")).build();
    }
}
