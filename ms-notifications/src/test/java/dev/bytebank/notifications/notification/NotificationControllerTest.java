package dev.bytebank.notifications.notification;

import dev.bytebank.common.BytebankProperties;
import dev.bytebank.common.error.GlobalExceptionHandler;
import dev.bytebank.common.security.JwtVerifier;
import dev.bytebank.common.security.Role;
import dev.bytebank.common.security.SecurityConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, JwtVerifier.class, GlobalExceptionHandler.class})
@EnableConfigurationProperties(BytebankProperties.class)
@TestPropertySource(properties = {
        "bytebank.internal-key=clave-interna",
        "bytebank.jwt.secret=secreto-de-test-con-al-menos-32-caracteres"
})
public class NotificationControllerTest {

    private static final String SECRET = "secreto-de-test-con-al-menos-32-caracteres";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    NotificationService notificationService;

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
    @DisplayName("GET /api/notifications sin token rechaza con 401 NO_AUTENTICADO")
    void listWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/notifications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("GET /api/notifications con token válido devuelve listado paginado (200 OK)")
    void listWithValidTokenReturnsPaginatedNotifications() throws Exception {
        Notification notification = new Notification(
                1L, 42L, "ES7600000000000000000000", "DEPOSITO",
                new BigDecimal("150.00"), new BigDecimal("1150.00"),
                "REF-001", "Depósito recibido por 150.00 €", Instant.now()
        );

        PageRequest pageable = PageRequest.of(0, 20);
        when(notificationService.listForUser(eq(1L), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(notification), pageable, 1));

        String token = token(1L, "cliente@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].iban").value("ES7600000000000000000000"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("GET /api/notifications con page negativo rechaza con 400 DATOS_NO_VALIDOS")
    void listWithNegativePageReturnsBadRequest() throws Exception {
        String token = token(1L, "cliente@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/notifications")
                        .param("page", "-1")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
    }

    @Test
    @DisplayName("GET /api/notifications con size menor a 1 rechaza con 400 DATOS_NO_VALIDOS")
    void listWithSizeLessThanOneReturnsBadRequest() throws Exception {
        String token = token(1L, "cliente@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/notifications")
                        .param("size", "0")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
    }

    @Test
    @DisplayName("GET /api/notifications con size mayor a 100 rechaza con 400 DATOS_NO_VALIDOS")
    void listWithSizeGreaterThan100ReturnsBadRequest() throws Exception {
        String token = token(1L, "cliente@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/notifications")
                        .param("size", "101")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
    }
}
