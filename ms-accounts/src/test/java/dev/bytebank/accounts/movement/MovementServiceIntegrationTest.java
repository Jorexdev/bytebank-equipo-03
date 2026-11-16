package dev.bytebank.accounts.movement;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.account.AccountRepository;
import dev.bytebank.common.iban.Iban;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MovementServiceIntegrationTest extends IntegrationTest {

    private static final String JWT_SECRET = "secreto-de-test-con-al-menos-32-caracteres";
    private static final long OWNER_ID = 2L;

    @Autowired
    MovementService movementService;
    @Autowired
    AccountRepository accounts;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mockMvc;

    private Account account;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");
        account = accounts.save(new Account(Iban.generate(accounts.nextAccountNumber()), OWNER_ID));
        ownerToken = Jwts.builder()
                .subject(String.valueOf(OWNER_ID))
                .claim("email", "ana@bytebank.test")
                .claim("role", "CLIENTE")
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    @DisplayName("BUG-03 (BD Real): una cuenta con 5 movimientos devuelve los 5 en la primera página (no 4)")
    void returnsAllFiveMovementsOnFirstPage() throws Exception {
        Instant base = Instant.parse("2026-05-10T10:00:00Z");
        for (int i = 1; i <= 5; i++) {
            insertMovement("Mov " + i, base.plusSeconds(i * 60L), i * 10);
        }

        mockMvc.perform(get("/api/accounts/" + account.getIban() + "/movements")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("page", "0")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[0].concept").value("Mov 5"))
                .andExpect(jsonPath("$.content[4].concept").value("Mov 1"));
    }

    @Test
    @DisplayName("BUG-03 (BD Real): paginar de 20 en 20 devuelve todos los movimientos sin perder ninguno entre páginas")
    void paginatingByTwentyReturnsAllMovementsAcrossPagesWithoutGaps() {
        Instant base = Instant.parse("2026-05-01T10:00:00Z");
        for (int i = 1; i <= 45; i++) {
            insertMovement("Mov " + i, base.plusSeconds(i * 60L), i * 10);
        }

        var page0 = movementService.statement(account, null, null, 0, 20);
        var page1 = movementService.statement(account, null, null, 1, 20);
        var page2 = movementService.statement(account, null, null, 2, 20);

        assertThat(page0.totalElements()).isEqualTo(45);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.content()).hasSize(20);
        assertThat(page1.content()).hasSize(20);
        assertThat(page2.content()).hasSize(5);

        List<String> allConcepts = new ArrayList<>();
        page0.content().forEach(m -> allConcepts.add(m.concept()));
        page1.content().forEach(m -> allConcepts.add(m.concept()));
        page2.content().forEach(m -> allConcepts.add(m.concept()));

        Set<String> uniqueConcepts = new HashSet<>(allConcepts);
        assertThat(allConcepts).hasSize(45);
        assertThat(uniqueConcepts).hasSize(45);
        assertThat(allConcepts.get(0)).isEqualTo("Mov 45");
        assertThat(allConcepts.get(44)).isEqualTo("Mov 1");
    }

    @Test
    @DisplayName("BUG-03 (BD Real): filtrar del 1 al 31 de mayo incluye movimientos de todo el día 31 en hora Europe/Madrid")
    void dateFilterIncludesLastDayInMadridTimezone() {
        // 2026-04-30 23:59:59 Europe/Madrid (UTC+2) -> fuera antes
        insertMovement("Fuera abril", Instant.parse("2026-04-30T21:59:59Z"), 10);
        // 2026-05-01 00:30:00 Europe/Madrid (UTC+2) -> dentro inicio de mayo
        insertMovement("Dentro inicio mayo", Instant.parse("2026-04-30T22:30:00Z"), 20);
        // 2026-05-31 23:30:00 Europe/Madrid (UTC+2) -> dentro final de mayo
        insertMovement("Dentro fin mayo", Instant.parse("2026-05-31T21:30:00Z"), 30);
        // 2026-06-01 00:00:00 Europe/Madrid (UTC+2) -> fuera después
        insertMovement("Fuera junio", Instant.parse("2026-05-31T22:00:00Z"), 40);

        var page = movementService.statement(account, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 0, 20);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content())
                .extracting(MovementResponse::concept)
                .containsExactly("Dentro fin mayo", "Dentro inicio mayo");
    }

    @Test
    @DisplayName("BUG-03 (HTTP): parámetros de paginación o rango de fechas inválidos devuelven 400 DATOS_NO_VALIDOS")
    void invalidQueryParamsReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/accounts/" + account.getIban() + "/movements")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("page", "-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));

        mockMvc.perform(get("/api/accounts/" + account.getIban() + "/movements")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("size", "0")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));

        mockMvc.perform(get("/api/accounts/" + account.getIban() + "/movements")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("size", "101")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));

        mockMvc.perform(get("/api/accounts/" + account.getIban() + "/movements")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("from", "2026-05-31")
                        .param("to", "2026-05-01")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGO_FECHAS_INVALIDO"));
    }

    private void insertMovement(String concept, Instant createdAt, int balanceAfter) {
        jdbc.update("""
                INSERT INTO movements (account_id, type, amount, balance_after, concept, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, account.getId(), "INGRESO", 10, balanceAfter, concept, Timestamp.from(createdAt));
    }
}
