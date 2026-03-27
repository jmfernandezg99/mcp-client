package org.acme.mcp.service;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class JwtService {

    public String generateToken(UUID userId, String username) {
        // La duración (lifespan) y el issuer ya están en application.properties
        return Jwt.subject(userId.toString())
                .claim("username", username)
                .sign();
    }
}
