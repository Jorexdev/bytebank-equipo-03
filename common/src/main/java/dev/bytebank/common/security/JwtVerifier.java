package dev.bytebank.common.security;

import dev.bytebank.common.BytebankProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Valida los JWT HS256 que emite ms-auth. El secreto llega por configuración (JWT_SECRET). */
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(BytebankProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    public Optional<AuthUser> verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

            String subject = claims.getSubject();
            String email = claims.get("email", String.class);
            String roleName = claims.get("role", String.class);
            if (subject == null || email == null || roleName == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthUser(Long.valueOf(subject), email, Role.valueOf(roleName)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

