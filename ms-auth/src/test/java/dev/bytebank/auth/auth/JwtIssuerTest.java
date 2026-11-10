package dev.bytebank.auth.auth;

import dev.bytebank.auth.user.User;
import dev.bytebank.common.BytebankProperties;
import dev.bytebank.common.security.JwtVerifier;
import dev.bytebank.common.security.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtIssuerTest {

    private static final String SECRET = "secreto-de-test-con-al-menos-32-caracteres";

    private static BytebankProperties properties(Long expirationMinutes) {
        return new BytebankProperties(new BytebankProperties.Jwt(SECRET, expirationMinutes), "clave-interna", null);
    }

    private static User user() {
        User user = new User("ana@bytebank.test", "hash", "Ana", Role.CLIENTE);
        ReflectionTestUtils.setField(user, "id", 7L);
        return user;
    }

    private static Claims parse(String token) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getPayload();
    }

    @Test
    void issuedTokenExpiresInSixtyMinutes() {
        JwtIssuer issuer = new JwtIssuer(properties(60L));

        Claims claims = parse(issuer.issue(user()));

        long ttlMillis = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(ttlMillis).isEqualTo(Duration.ofMinutes(60).toMillis());
        assertThat(issuer.expirationSeconds()).isEqualTo(3600);
    }

    @Test
    void defaultsToSixtyMinutesWhenNotConfigured() {
        assertThat(new JwtIssuer(properties(null)).expirationSeconds()).isEqualTo(3600);
    }

    @Test
    void issuedTokenCarriesTheUserDataAndIsAcceptedByTheVerifier() {
        String token = new JwtIssuer(properties(60L)).issue(user());

        var authUser = new JwtVerifier(properties(60L)).verify(token);

        assertThat(authUser).isPresent();
        assertThat(authUser.get().id()).isEqualTo(7L);
        assertThat(authUser.get().email()).isEqualTo("ana@bytebank.test");
        assertThat(authUser.get().role()).isEqualTo(Role.CLIENTE);
    }
}