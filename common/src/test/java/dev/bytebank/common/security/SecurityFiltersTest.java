package dev.bytebank.common.security;

import dev.bytebank.common.BytebankProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityFiltersTest {

    private static final String SECRET = "secreto-de-test-con-al-menos-32-caracteres";
    private static final String INTERNAL_KEY = "clave-interna-de-test";

    private final InternalKeyFilter internalFilter = new InternalKeyFilter(INTERNAL_KEY);
    private final JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(
            new JwtVerifier(new BytebankProperties(
                    new BytebankProperties.Jwt(SECRET, 60L),
                    INTERNAL_KEY,
                    new BytebankProperties.Security(List.of()))));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void internalRouteKeepsInternalRoleEvenWhenBearerJwtIsAlsoPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/accounts/ES7639990001000000000001");
        request.setServletPath("/internal/accounts/ES7639990001000000000001");
        request.addHeader(InternalKeyFilter.HEADER, INTERNAL_KEY);
        request.addHeader("Authorization", "Bearer " + clientToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        internalFilter.doFilter(request, response, (req, res) -> jwtFilter.doFilter(req, res, chain));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly("ROLE_INTERNAL");
    }

    @Test
    void internalRouteDoesNotAuthenticateClientJwtWithoutInternalKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/accounts/ES7639990001000000000001");
        request.setServletPath("/internal/accounts/ES7639990001000000000001");
        request.addHeader("Authorization", "Bearer " + clientToken());
        MockHttpServletResponse response = new MockHttpServletResponse();

        internalFilter.doFilter(request, response, (req, res) -> jwtFilter.doFilter(req, res, mock(FilterChain.class)));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void apiRouteAuthenticatesJwtAndIgnoresInternalKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.setServletPath("/api/accounts");
        request.addHeader(InternalKeyFilter.HEADER, INTERNAL_KEY);
        request.addHeader("Authorization", "Bearer " + clientToken());
        MockHttpServletResponse response = new MockHttpServletResponse();

        internalFilter.doFilter(request, response, (req, res) -> jwtFilter.doFilter(req, res, mock(FilterChain.class)));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly("ROLE_CLIENTE");
    }

    private static String clientToken() {
        return Jwts.builder()
                .subject("2")
                .claim("email", "ana@bytebank.test")
                .claim("role", Role.CLIENTE.name())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
