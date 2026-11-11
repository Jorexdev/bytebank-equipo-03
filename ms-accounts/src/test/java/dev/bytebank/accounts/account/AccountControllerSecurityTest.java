package dev.bytebank.accounts.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytebank.accounts.movement.MovementService;
import dev.bytebank.common.BytebankProperties;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.error.GlobalExceptionHandler;
import dev.bytebank.common.security.AuthUser;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, JwtVerifier.class, GlobalExceptionHandler.class})
@EnableConfigurationProperties(BytebankProperties.class)
@TestPropertySource(properties = {
        "bytebank.internal-key=clave-interna",
        "bytebank.jwt.secret=secreto-de-test-con-al-menos-32-caracteres"
})
class AccountControllerSecurityTest {

    private static final String SECRET = "secreto-de-test-con-al-menos-32-caracteres";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AccountService accountService;

    @MockBean
    MovementService movementService;

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
    @DisplayName("SEG-01 (Autenticación): GET /api/accounts/{iban} sin token rechaza con 401 NO_AUTENTICADO")
    void getAccountWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/accounts/ES9121000418450200051332")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("SEG-01 (IDOR): GET /api/accounts/{iban} permite al titular ver su propia cuenta (200 OK)")
    void getOwnAccountReturnsOk() throws Exception {
        String iban = "ES9121000418450200051332";
        Account account = mock(Account.class);
        when(account.getIban()).thenReturn(iban);
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVA);
        when(account.getBalance()).thenReturn(1500.0);
        when(account.getOwnerId()).thenReturn(1L);
        when(account.getCreatedAt()).thenReturn(Instant.now());

        when(accountService.getForUser(eq(iban), any(AuthUser.class))).thenReturn(account);

        String token = token(1L, "titular@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/accounts/" + iban)
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iban").value(iban));
    }

    @Test
    @DisplayName("SEG-01 (IDOR): GET /api/accounts/{iban} permite a un GESTOR ver cualquier cuenta (200 OK)")
    void getAccountAsGestorReturnsOk() throws Exception {
        String iban = "ES9121000418450200051332";
        Account account = mock(Account.class);
        when(account.getIban()).thenReturn(iban);
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVA);
        when(account.getBalance()).thenReturn(1500.0);
        when(account.getOwnerId()).thenReturn(1L);
        when(account.getCreatedAt()).thenReturn(Instant.now());

        when(accountService.getForUser(eq(iban), any(AuthUser.class))).thenReturn(account);

        String gestorToken = token(99L, "gestor@bytebank.test", Role.GESTOR);

        mockMvc.perform(get("/api/accounts/" + iban)
                        .header("Authorization", "Bearer " + gestorToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iban").value(iban));
    }

    @Test
    @DisplayName("SEG-01 (IDOR): GET /api/accounts/{iban} rechaza con 403 a un CLIENTE que consulta una cuenta ajena")
    void getOtherUserAccountReturnsForbidden() throws Exception {
        String iban = "ES9121000418450200051332";
        when(accountService.getForUser(eq(iban), any(AuthUser.class)))
                .thenThrow(ApiException.forbidden("No tienes permiso para ver esta cuenta"));

        String client2Token = token(2L, "cliente2@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/accounts/" + iban)
                        .header("Authorization", "Bearer " + client2Token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("SEG-01 (IDOR): GET /api/accounts/{iban}/movements rechaza con 403 a un CLIENTE que consulta extracto ajeno")
    void getMovementsOtherUserAccountReturnsForbidden() throws Exception {
        String iban = "ES9121000418450200051332";
        when(accountService.getForUser(eq(iban), any(AuthUser.class)))
                .thenThrow(ApiException.forbidden("No tienes permiso para ver esta cuenta"));

        String client2Token = token(2L, "cliente2@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/accounts/" + iban + "/movements")
                        .header("Authorization", "Bearer " + client2Token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("SEG-01 (IDOR): GET /api/accounts/{iban}/movements permite al titular ver sus movimientos (200 OK)")
    void getMovementsOwnAccountReturnsOk() throws Exception {
        String iban = "ES9121000418450200051332";
        Account account = mock(Account.class);
        when(accountService.getForUser(eq(iban), any(AuthUser.class))).thenReturn(account);
        when(movementService.statement(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        String token = token(1L, "titular@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/accounts/" + iban + "/movements")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEG-01 (Autorización): PATCH /api/accounts/{iban}/status rechaza con 403 si un CLIENTE intenta cambiar el estado")
    void changeStatusByClienteReturnsForbidden() throws Exception {
        String iban = "ES9121000418450200051332";
        String clientToken = token(1L, "cliente@bytebank.test", Role.CLIENTE);

        mockMvc.perform(patch("/api/accounts/" + iban + "/status")
                        .header("Authorization", "Bearer " + clientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"BLOQUEADA\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("SEG-01 (Autorización): PATCH /api/accounts/{iban}/status permite a un GESTOR cambiar el estado (200 OK)")
    void changeStatusByGestorReturnsOk() throws Exception {
        String iban = "ES9121000418450200051332";
        Account account = mock(Account.class);
        when(account.getIban()).thenReturn(iban);
        when(account.getStatus()).thenReturn(AccountStatus.BLOQUEADA);
        when(account.getBalance()).thenReturn(1500.0);
        when(account.getOwnerId()).thenReturn(1L);
        when(account.getCreatedAt()).thenReturn(Instant.now());

        when(accountService.changeStatus(eq(iban), eq(AccountStatus.BLOQUEADA))).thenReturn(account);

        String gestorToken = token(99L, "gestor@bytebank.test", Role.GESTOR);

        mockMvc.perform(patch("/api/accounts/" + iban + "/status")
                        .header("Authorization", "Bearer " + gestorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"BLOQUEADA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOQUEADA"));
    }
}
