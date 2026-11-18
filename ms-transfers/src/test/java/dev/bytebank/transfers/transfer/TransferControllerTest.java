package dev.bytebank.transfers.transfer;

import dev.bytebank.common.BytebankProperties;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.error.GlobalExceptionHandler;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.JwtVerifier;
import dev.bytebank.common.security.Role;
import dev.bytebank.common.security.SecurityConfig;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.transfers.transfer.TransferDtos.TransferResponse;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import({SecurityConfig.class, JwtVerifier.class, GlobalExceptionHandler.class})
@EnableConfigurationProperties(BytebankProperties.class)
@TestPropertySource(properties = {
        "bytebank.internal-key=clave-interna",
        "bytebank.jwt.secret=secreto-de-test-con-al-menos-32-caracteres"
})
class TransferControllerTest {

    private static final String SECRET = "secreto-de-test-con-al-menos-32-caracteres";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransferService transferService;

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
    @DisplayName("GET /api/transfers con page negativo rechaza con 400 DATOS_NO_VALIDOS")
    void listWithNegativePageReturnsBadRequest() throws Exception {
        String token = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/transfers")
                        .param("page", "-1")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
    }

    @Test
    @DisplayName("GET /api/transfers con size menor a 1 rechaza con 400 DATOS_NO_VALIDOS")
    void listWithSizeLessThanOneReturnsBadRequest() throws Exception {
        String token = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/transfers")
                        .param("size", "0")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
    }

    @Test
    @DisplayName("GET /api/transfers con size mayor a 100 rechaza con 400 DATOS_NO_VALIDOS")
    void listWithSizeGreaterThanLimitReturnsBadRequest() throws Exception {
        String token = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/transfers")
                        .param("size", "101")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
    }

    @Test
    @DisplayName("GET /api/transfers con token válido devuelve listado paginado (200 OK)")
    void listWithValidTokenReturnsPaginatedTransfers() throws Exception {
        TransferResponse response = new TransferResponse(1L, Iban.generate(1), Iban.generate(2),
                new BigDecimal("100.00"), "Cena", TransferStatus.COMPLETADA, null, Instant.now());
        when(transferService.list(eq(0), eq(20), any()))
                .thenReturn(new PageResponse<>(List.of(response), 0, 20, 1, 1));

        String token = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].status").value("COMPLETADA"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("POST /api/transfers con importe de 3 decimales rechaza con 400 DATOS_NO_VALIDOS")
    void createWithAmountBelowMinimumReturnsBadRequest() throws Exception {
        String token = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originIban": "%s", "destinationIban": "%s", "amount": 0.001, "concept": "Cena"}
                                """.formatted(Iban.generate(1), Iban.generate(2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"))
                .andExpect(jsonPath("$.fields.amount").exists());
    }

    @Test
    @DisplayName("POST /api/transfers con demasiados dígitos enteros rechaza con 400 DATOS_NO_VALIDOS")
    void createWithTooManyIntegerDigitsReturnsBadRequest() throws Exception {
        String token = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originIban": "%s", "destinationIban": "%s", "amount": 10000000000000000.00, "concept": "Cena"}
                                """.formatted(Iban.generate(1), Iban.generate(2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"))
                .andExpect(jsonPath("$.fields.amount").exists());
    }

    @Test
    @DisplayName("GET /api/transfers/{id} de otro usuario rechaza con 403 ACCESO_DENEGADO")
    void getTransferFromAnotherUserReturnsForbidden() throws Exception {
        when(transferService.get(eq(1L), any()))
                .thenThrow(ApiException.forbidden("No puedes ver esta transferencia"));

        String token = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/transfers/1")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("GET /api/transfers/{id} inexistente rechaza con 404 TRANSFERENCIA_NO_ENCONTRADA")
    void getUnknownTransferReturnsNotFound() throws Exception {
        when(transferService.get(eq(1L), any()))
                .thenThrow(ApiException.notFound("TRANSFERENCIA_NO_ENCONTRADA", "No existe la transferencia 1"));

        String token = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(get("/api/transfers/1")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFERENCIA_NO_ENCONTRADA"));
    }

    @Test
    @DisplayName("POST /api/transfers con datos válidos devuelve 201 CREATED")
    void createWithValidDataReturnsCreated() throws Exception {
        Transfer transfer = new Transfer(Iban.generate(1), Iban.generate(2),
                new BigDecimal("100.00"), "Cena", 2L);
        when(transferService.create(any(), any())).thenReturn(transfer);

        String token = token(2L, "ana@bytebank.test", Role.CLIENTE);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originIban": "%s", "destinationIban": "%s", "amount": 100.00, "concept": "Cena"}
                                """.formatted(Iban.generate(1), Iban.generate(2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETADA"))
                .andExpect(jsonPath("$.amount").value(100.00));
    }
}
