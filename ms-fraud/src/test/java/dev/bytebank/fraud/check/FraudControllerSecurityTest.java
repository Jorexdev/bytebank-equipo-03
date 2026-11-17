package dev.bytebank.fraud.check;

import dev.bytebank.common.BytebankProperties;
import dev.bytebank.common.error.GlobalExceptionHandler;
import dev.bytebank.common.security.JwtVerifier;
import dev.bytebank.common.security.Role;
import dev.bytebank.common.security.SecurityConfig;
import dev.bytebank.common.web.PageResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudController.class)
@Import({SecurityConfig.class, JwtVerifier.class, GlobalExceptionHandler.class})
@EnableConfigurationProperties(BytebankProperties.class)
@TestPropertySource(properties = {
        "bytebank.internal-key=clave-interna",
        "bytebank.jwt.secret=secreto-de-test-con-al-menos-32-caracteres"
})
class FraudControllerSecurityTest {

    private static final String SECRET = "secreto-de-test-con-al-menos-32-caracteres";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FraudService fraudService;

    private String token(Long userId, String email, Role role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role.name())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    @DisplayName("SEG-01 (Autenticación): GET /api/fraud/checks sin token rechaza con 401 NO_AUTENTICADO")
    void listWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/fraud/checks").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));

        verifyNoInteractions(fraudService);
    }

    @Test
    @DisplayName("SEG-01 (Autorización): GET /api/fraud/checks rechaza con 403 a un CLIENTE")
    void listAsClienteReturnsForbidden() throws Exception {
        String clientToken = token(1L, "cliente@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/fraud/checks")
                        .header("Authorization", "Bearer " + clientToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));

        verifyNoInteractions(fraudService);
    }

    @Test
    @DisplayName("SEG-01 (IDOR): un CLIENTE no puede leer el historial de otra cuenta pasando su IBAN")
    void listOtherUserChecksAsClienteReturnsForbidden() throws Exception {
        String clientToken = token(2L, "cliente2@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/fraud/checks")
                        .param("iban", "ES9539990001000000000001")
                        .header("Authorization", "Bearer " + clientToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));

        verifyNoInteractions(fraudService);
    }

    @Test
    @DisplayName("SEG-01 (Autorización): GET /api/fraud/checks permite a un GESTOR consultar el historial (200 OK)")
    void listAsGestorReturnsOk() throws Exception {
        when(fraudService.list(any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        String gestorToken = token(99L, "gestor@bytebank.test", Role.GESTOR);

        mockMvc.perform(get("/api/fraud/checks")
                        .header("Authorization", "Bearer " + gestorToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content").isArray());
    }
}
