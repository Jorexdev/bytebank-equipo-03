package dev.bytebank.accounts.account;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.client.AuthClient;
import dev.bytebank.accounts.client.AuthClient.UserInfo;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AccountHoldersIntegrationTest extends IntegrationTest {

    private static final String JWT_SECRET = "secreto-de-test-con-al-menos-32-caracteres";
    private static final String INTERNAL_KEY = "clave-interna-de-test";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AccountService accountService;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @MockBean
    AuthClient authClient;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");
    }

    private String token(Long userId, String email, Role role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role.name())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    @DisplayName("CAM-01 (BD + HTTP): permite agregar titulares hasta 4 fuera de transacción y rechaza el 5to sin consultar ms-auth")
    void addsHoldersUpToFourAndRejectsFifthWithoutCallingAuth() throws Exception {
        String anaToken = token(2L, "ana@bytebank.test", Role.CLIENTE);
        String luisToken = token(3L, "luis@bytebank.test", Role.CLIENTE);
        String mariaToken = token(4L, "maria@bytebank.test", Role.CLIENTE);

        AtomicBoolean txActiveDuringAuthCall = new AtomicBoolean(true);
        when(authClient.findByEmail("luis@bytebank.test")).thenAnswer(inv -> {
            txActiveDuringAuthCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new UserInfo(3L, "luis@bytebank.test", "Luis", Role.CLIENTE);
        });
        when(authClient.findByEmail("maria@bytebank.test"))
                .thenReturn(new UserInfo(4L, "maria@bytebank.test", "María", Role.CLIENTE));
        when(authClient.findByEmail("carlos@bytebank.test"))
                .thenReturn(new UserInfo(5L, "carlos@bytebank.test", "Carlos", Role.CLIENTE));

        String responseBody = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + anaToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(2))
                .andExpect(jsonPath("$.holderIds.length()").value(1))
                .andExpect(jsonPath("$.holderIds[0]").value(2))
                .andReturn().getResponse().getContentAsString();

        String iban = com.jayway.jsonpath.JsonPath.read(responseBody, "$.iban");

        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"luis@bytebank.test\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holderIds.length()").value(2))
                .andExpect(jsonPath("$.holderIds[0]").value(2))
                .andExpect(jsonPath("$.holderIds[1]").value(3));

        assertThat(txActiveDuringAuthCall.get()).isFalse();

        // Un co-titular (Luis) agrega al 3er titular (María)
        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + luisToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"maria@bytebank.test\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holderIds.length()").value(3))
                .andExpect(jsonPath("$.holderIds[0]").value(2))
                .andExpect(jsonPath("$.holderIds[1]").value(3))
                .andExpect(jsonPath("$.holderIds[2]").value(4));

        // María agrega al 4to titular (Carlos)
        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + mariaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"carlos@bytebank.test\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holderIds.length()").value(4))
                .andExpect(jsonPath("$.holderIds[0]").value(2))
                .andExpect(jsonPath("$.holderIds[1]").value(3))
                .andExpect(jsonPath("$.holderIds[2]").value(4))
                .andExpect(jsonPath("$.holderIds[3]").value(5));

        // Con 4 titulares, rechaza el 5to ANTES de llamar a ms-auth (evita oráculo de enumeración)
        reset(authClient);
        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"elena@bytebank.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAXIMO_TITULARES_ALCANZADO"));
        verifyNoInteractions(authClient);

        Integer countInDb = jdbc.queryForObject(
                "select count(*) from account_holders ah join accounts a on a.id = ah.account_id where a.iban = ?",
                Integer.class, iban);
        assertThat(countInDb).isEqualTo(4);
    }

    @Test
    @DisplayName("CAM-01 (Aislamiento y orden en GET /api/accounts): devuelve propias + compartidas en orden, sin cuentas ajenas")
    void listOwnIsolatesFromOtherUsersAndOrdersAscendingWithAllHolders() throws Exception {
        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
        AuthUser luis = new AuthUser(3L, "luis@bytebank.test", Role.CLIENTE);
        AuthUser carlos = new AuthUser(5L, "carlos@bytebank.test", Role.CLIENTE);

        String sharedIban = accountService.open(ana).getIban();
        when(authClient.findByEmail("luis@bytebank.test"))
                .thenReturn(new UserInfo(3L, "luis@bytebank.test", "Luis", Role.CLIENTE));
        accountService.addHolder(sharedIban, "luis@bytebank.test", ana);

        // Asegurar createdAt estrictamente posterior para probar ORDER BY createdAt ASC
        String luisOwnIban = accountService.open(luis).getIban();
        jdbc.update("update accounts set created_at = created_at - interval '10 minutes' where iban = ?", sharedIban);
        jdbc.update("update accounts set created_at = created_at - interval '5 minutes' where iban = ?", luisOwnIban);

        // Cuenta ajena de Carlos que Luis NO debe ver nunca
        accountService.open(carlos);

        String luisToken = token(3L, "luis@bytebank.test", Role.CLIENTE);
        String anaToken = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + luisToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].iban").value(sharedIban))
                .andExpect(jsonPath("$[0].ownerId").value(2))
                .andExpect(jsonPath("$[0].holderIds.length()").value(2))
                .andExpect(jsonPath("$[0].holderIds[0]").value(2))
                .andExpect(jsonPath("$[0].holderIds[1]").value(3))
                .andExpect(jsonPath("$[1].iban").value(luisOwnIban))
                .andExpect(jsonPath("$[1].ownerId").value(3))
                .andExpect(jsonPath("$[1].holderIds.length()").value(1))
                .andExpect(jsonPath("$[1].holderIds[0]").value(3));

        // Ana solo ve la compartida, nunca la cuenta propia de Luis ni la de Carlos
        mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + anaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].iban").value(sharedIban));
    }

    @Test
    @DisplayName("CAM-01 (BD + HTTP): co-titular ve detalle, extracto, /internal/accounts y PATCH /status funciona con open-in-view=false")
    void sharedAccountDetailMovementsInternalAndStatusChangeWorkEndToEnd() throws Exception {
        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
        String iban = accountService.open(ana).getIban();

        when(authClient.findByEmail("luis@bytebank.test"))
                .thenReturn(new UserInfo(3L, "luis@bytebank.test", "Luis", Role.CLIENTE));
        accountService.addHolder(iban, "luis@bytebank.test", ana);

        String gestorToken = token(1L, "gestor@bytebank.test", Role.GESTOR);
        String luisToken = token(3L, "luis@bytebank.test", Role.CLIENTE);
        String anaToken = token(2L, "ana@bytebank.test", Role.CLIENTE);
        String intruderToken = token(99L, "ajeno@bytebank.test", Role.CLIENTE);

        mockMvc.perform(post("/api/accounts/" + iban + "/deposits")
                        .header("Authorization", "Bearer " + gestorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 250.00, \"concept\": \"Fondo común\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderIds.length()").value(2))
                .andExpect(jsonPath("$.holderIds[0]").value(2))
                .andExpect(jsonPath("$.holderIds[1]").value(3));

        mockMvc.perform(get("/api/accounts/" + iban)
                        .header("Authorization", "Bearer " + luisToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.00))
                .andExpect(jsonPath("$.holderIds[0]").value(2))
                .andExpect(jsonPath("$.holderIds[1]").value(3));

        mockMvc.perform(get("/api/accounts/" + iban + "/movements")
                        .header("Authorization", "Bearer " + luisToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].concept").value("Fondo común"))
                .andExpect(jsonPath("$.content[0].amount").value(250.00));

        mockMvc.perform(get("/api/accounts/" + iban)
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));

        mockMvc.perform(get("/api/accounts/" + iban + "/movements")
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));

        mockMvc.perform(get("/internal/accounts/" + iban)
                        .header("X-Internal-Key", INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iban").value(iban))
                .andExpect(jsonPath("$.ownerId").value(2))
                .andExpect(jsonPath("$.holderIds[0]").value(2))
                .andExpect(jsonPath("$.holderIds[1]").value(3));

        // PATCH /status con JPA real (open-in-view: false) debe devolver holderIds sin LazyInitializationException
        mockMvc.perform(patch("/api/accounts/" + iban + "/status")
                        .header("Authorization", "Bearer " + gestorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"BLOQUEADA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOQUEADA"))
                .andExpect(jsonPath("$.holderIds.length()").value(2))
                .andExpect(jsonPath("$.holderIds[0]").value(2))
                .andExpect(jsonPath("$.holderIds[1]").value(3));

        // Tras bloquearla, añadir titular falla con 409 CUENTA_NO_OPERATIVA sin llamar a ms-auth
        reset(authClient);
        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"maria@bytebank.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUENTA_NO_OPERATIVA"));
        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("CAM-01 (Errores HTTP): 403 no titular, 404 cuenta/usuario, 400 GESTOR, 409 auto-agregado y 503 ms-auth caído")
    void handlesAllHttpErrorPathsCleanly() throws Exception {
        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
        String iban = accountService.open(ana).getIban();

        String anaToken = token(2L, "ana@bytebank.test", Role.CLIENTE);
        String intruderToken = token(99L, "ajeno@bytebank.test", Role.CLIENTE);

        // No titular -> 403 sin llamar ms-auth
        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + intruderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"alguien@bytebank.test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
        verifyNoInteractions(authClient);

        // Cuenta inexistente -> 404 sin llamar ms-auth
        mockMvc.perform(post("/api/accounts/ES9121000418450200051332/holders")
                        .header("Authorization", "Bearer " + anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"alguien@bytebank.test\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUENTA_NO_ENCONTRADA"));
        verifyNoInteractions(authClient);

        // Auto-agregarse -> 409 sin llamar ms-auth
        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"ana@bytebank.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TITULAR_YA_EXISTENTE"));
        verifyNoInteractions(authClient);

        // Destino GESTOR -> 400 USUARIO_NO_ES_CLIENTE
        when(authClient.findByEmail("gestor@bytebank.test"))
                .thenReturn(new UserInfo(1L, "gestor@bytebank.test", "Gestor", Role.GESTOR));
        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"gestor@bytebank.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USUARIO_NO_ES_CLIENTE"));

        // Usuario no encontrado en ms-auth -> 404 USUARIO_NO_ENCONTRADO
        when(authClient.findByEmail("noexiste@bytebank.test"))
                .thenThrow(ApiException.notFound("USUARIO_NO_ENCONTRADO", "No existe un usuario con ese email"));
        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"noexiste@bytebank.test\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USUARIO_NO_ENCONTRADO"));

        // ms-auth caído -> 503 SERVICIO_NO_DISPONIBLE
        when(authClient.findByEmail("caido@bytebank.test"))
                .thenThrow(new RemoteUnavailableException("El servicio de usuarios no está disponible temporalmente"));
        mockMvc.perform(post("/api/accounts/" + iban + "/holders")
                        .header("Authorization", "Bearer " + anaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"caido@bytebank.test\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICIO_NO_DISPONIBLE"));
    }

    @Test
    @DisplayName("CAM-01 (Concurrencia FOR UPDATE): 6 hilos añadiendo clientes distintos desde 3 titulares nunca superan 4")
    void concurrentAddHolderRequestsNeverExceedMaxFourHolders() throws Exception {
        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
        String iban = accountService.open(ana).getIban();

        when(authClient.findByEmail("u3@bytebank.test")).thenReturn(new UserInfo(3L, "u3@bytebank.test", "U3", Role.CLIENTE));
        when(authClient.findByEmail("u4@bytebank.test")).thenReturn(new UserInfo(4L, "u4@bytebank.test", "U4", Role.CLIENTE));
        accountService.addHolder(iban, "u3@bytebank.test", ana);
        accountService.addHolder(iban, "u4@bytebank.test", ana);

        int threads = 6;
        for (int i = 0; i < threads; i++) {
            long userId = 10L + i;
            String email = "concurrent" + i + "@bytebank.test";
            when(authClient.findByEmail(email)).thenReturn(new UserInfo(userId, email, "User " + userId, Role.CLIENTE));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String email = "concurrent" + i + "@bytebank.test";
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        accountService.addHolder(iban, email, ana);
                        return "ADDED";
                    } catch (ApiException e) {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        return e.getCode();
                    }
                }));
            }
            start.countDown();

            int added = 0;
            int maxReached = 0;
            for (Future<String> future : futures) {
                String outcome = future.get(30, TimeUnit.SECONDS);
                if ("ADDED".equals(outcome)) {
                    added++;
                } else if ("MAXIMO_TITULARES_ALCANZADO".equals(outcome)) {
                    maxReached++;
                }
            }

            assertThat(added).isEqualTo(1);
            assertThat(maxReached).isEqualTo(threads - 1);
            assertThat(accountService.get(iban).getHolderIds()).hasSize(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CAM-01 (Concurrencia FOR UPDATE): peticiones simultáneas añadiendo al mismo usuario devuelven 1 éxito y N-1 409 TITULAR_YA_EXISTENTE")
    void concurrentDuplicateHolderRequestsReturnConflictInsteadOf500() throws Exception {
        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
        String iban = accountService.open(ana).getIban();
        when(authClient.findByEmail("luis@bytebank.test"))
                .thenReturn(new UserInfo(3L, "luis@bytebank.test", "Luis", Role.CLIENTE));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        accountService.addHolder(iban, "luis@bytebank.test", ana);
                        return "ADDED";
                    } catch (ApiException e) {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        return e.getCode();
                    }
                }));
            }
            start.countDown();

            int added = 0;
            int duplicates = 0;
            for (Future<String> future : futures) {
                String outcome = future.get(30, TimeUnit.SECONDS);
                if ("ADDED".equals(outcome)) {
                    added++;
                } else if ("TITULAR_YA_EXISTENTE".equals(outcome)) {
                    duplicates++;
                }
            }

            assertThat(added).isEqualTo(1);
            assertThat(duplicates).isEqualTo(threads - 1);
            assertThat(accountService.get(iban).getHolderIds()).isEqualTo(List.of(2L, 3L));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CAM-01 (Migración Flyway V6): el backfill migra cuentas previas existentes a account_holders")
    void flywayV6BackfillsExistingAccountsIntoAccountHolders() {
        String schema = "v6_backfill_test";
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .target("5")
                    .load()
                    .migrate();

            Instant t1 = Instant.parse("2026-06-10T09:00:00Z");
            Instant t2 = Instant.parse("2026-06-11T14:30:00Z");
            String iban1 = Iban.generate(101);
            String iban2 = Iban.generate(102);

            jdbc.update("INSERT INTO " + schema + ".accounts (iban, status, owner_id, balance, created_at) VALUES (?, 'ACTIVA', ?, 100.00, ?)",
                    iban1, 2L, Timestamp.from(t1));
            jdbc.update("INSERT INTO " + schema + ".accounts (iban, status, owner_id, balance, created_at) VALUES (?, 'BLOQUEADA', ?, 0.00, ?)",
                    iban2, 7L, Timestamp.from(t2));

            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .target("6")
                    .load()
                    .migrate();

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT a.iban, ah.user_id, ah.added_at FROM " + schema + ".account_holders ah " +
                            "JOIN " + schema + ".accounts a ON a.id = ah.account_id ORDER BY ah.user_id ASC");

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("iban")).isEqualTo(iban1);
            assertThat(((Number) rows.get(0).get("user_id")).longValue()).isEqualTo(2L);
            assertThat(((Timestamp) rows.get(0).get("added_at")).toInstant()).isEqualTo(t1);
            assertThat(rows.get(1).get("iban")).isEqualTo(iban2);
            assertThat(((Number) rows.get(1).get("user_id")).longValue()).isEqualTo(7L);
            assertThat(((Timestamp) rows.get(1).get("added_at")).toInstant()).isEqualTo(t2);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    @DisplayName("CAM-01 (BD): la clave primaria compuesta de account_holders impide filas duplicadas a nivel PostgreSQL")
    void databasePrimaryKeyRejectsDuplicateAccountHolder() {
        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
        Account account = accountService.open(ana);

        assertThatThrownBy(() -> jdbc.update(
                "insert into account_holders (account_id, user_id, added_at) values (?, ?, now())",
                account.getId(), 2L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
