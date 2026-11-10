package dev.bytebank.common.security;

import dev.bytebank.common.BytebankProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtVerifierTest {

    private static final String SECRET = "secreto-de-test-con-al-menos-32-caracteres";

    private final JwtVerifier verifier =
            new JwtVerifier(new BytebankProperties(new BytebankProperties.Jwt(SECRET, 60L), "clave-interna", null));

    private static String token(String secret, String role) {
        return Jwts.builder()
                .subject("7")
                .claim("email", "ana@bytebank.test")
                .claim("role", role)
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void acceptsTokenSignedWithTheConfiguredSecret() {
        var user = verifier.verify(token(SECRET, "CLIENTE"));

        assertThat(user).isPresent();
        assertThat(user.get().id()).isEqualTo(7L);
        assertThat(user.get().role()).isEqualTo(Role.CLIENTE);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String forged = token("otro-secreto-distinto-de-al-menos-32-caracteres", "GESTOR");

        assertThat(verifier.verify(forged)).isEmpty();
    }

    @Test
    void rejectsGarbage() {
        assertThat(verifier.verify("esto-no-es-un-jwt")).isEmpty();
    }
}

