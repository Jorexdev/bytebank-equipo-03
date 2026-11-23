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

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

        mockMvc.perform(get("/api/transfers")
                        .param("page", "10001")
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
                new BigDecimal("100.00"), "Cena", TransferStatus.COMPLETADA, null, Instant.now(),
                null, null, null, null);
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

    @Test
    @DisplayName("GET /api/transfers/pending devuelve pendientes solo al gestor")
    void listPendingForGestorReturnsPaginatedTransfers() throws Exception {
        Transfer transfer = new Transfer(Iban.generate(1), Iban.generate(2),
                new BigDecimal("6000.00"), "Alquiler", 2L);
        transfer.pendingReview("TRANSFERENCIA_SUPERA_UMBRAL");
        when(transferService.listPending(eq(0), eq(20)))
                .thenReturn(new PageResponse<>(List.of(TransferResponse.from(transfer)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/transfers/pending")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PENDIENTE_REVISION"))
                .andExpect(jsonPath("$.content[0].reviewReason").value("TRANSFERENCIA_SUPERA_UMBRAL"))
                .andExpect(jsonPath("$.page").value(0));

        verify(transferService).listPending(0, 20);
    }

    @Test
    @DisplayName("GET /api/transfers/pending rechaza clientes")
    void listPendingForClienteReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/transfers/pending")
                        .header("Authorization", "Bearer " + token(2L, "ana@bytebank.test", Role.CLIENTE))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("GET /api/transfers/pending valida la paginación")
    void listPendingWithInvalidPageReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/transfers/pending")
                        .param("size", "101")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));

        mockMvc.perform(get("/api/transfers/pending")
                        .param("page", "10001")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/approve devuelve la transferencia revisada")
    void approveWithGestorReturnsTransfer() throws Exception {
        Transfer transfer = new Transfer(Iban.generate(1), Iban.generate(2),
                new BigDecimal("6000.00"), "Alquiler", 2L);
        transfer.complete();
        transfer.recordReview(99L);
        when(transferService.approve(eq(1L), any())).thenReturn(transfer);

        mockMvc.perform(post("/api/transfers/1/approve")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETADA"))
                .andExpect(jsonPath("$.reviewedBy").value(99))
                .andExpect(jsonPath("$.reviewedAt").exists());

        verify(transferService).approve(eq(1L), any());
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/approve rechaza clientes")
    void approveWithClienteReturnsForbidden() throws Exception {
        mockMvc.perform(post("/api/transfers/1/approve")
                        .header("Authorization", "Bearer " + token(2L, "ana@bytebank.test", Role.CLIENTE))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/approve propaga 404")
    void approveUnknownTransferReturnsNotFound() throws Exception {
        when(transferService.approve(eq(1L), any()))
                .thenThrow(ApiException.notFound("TRANSFERENCIA_NO_ENCONTRADA", "No existe la transferencia 1"));

        mockMvc.perform(post("/api/transfers/1/approve")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFERENCIA_NO_ENCONTRADA"));
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject devuelve rechazo auditado con comentario")
    void rejectWithGestorReturnsRejectedTransfer() throws Exception {
        Transfer transfer = new Transfer(Iban.generate(1), Iban.generate(2),
                new BigDecimal("6000.00"), "Alquiler", 2L);
        transfer.rejectByReviewer(99L, "Operación no justificada");
        when(transferService.reject(eq(1L), eq("Operación no justificada"), any())).thenReturn(transfer);

        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"comment":"Operación no justificada"}
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECHAZADA"))
                .andExpect(jsonPath("$.rejectionReason").value("RECHAZADA_POR_GESTOR"))
                .andExpect(jsonPath("$.reviewedBy").value(99))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty())
                .andExpect(jsonPath("$.reviewNote").value("Operación no justificada"));

        verify(transferService).reject(eq(1L), eq("Operación no justificada"), any());
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject acepta petición sin cuerpo ni Content-Type")
    void rejectWithoutBodyAndWithoutContentTypeReturnsRejectedTransfer() throws Exception {
        Transfer transfer = new Transfer(Iban.generate(1), Iban.generate(2),
                new BigDecimal("6000.00"), "Alquiler", 2L);
        transfer.rejectByReviewer(99L, null);
        when(transferService.reject(eq(1L), isNull(), any())).thenReturn(transfer);

        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECHAZADA"))
                .andExpect(jsonPath("$.rejectionReason").value("RECHAZADA_POR_GESTOR"))
                .andExpect(jsonPath("$.reviewedBy").value(99))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty())
                .andExpect(jsonPath("$.reviewNote").value(nullValue()));

        verify(transferService).reject(eq(1L), isNull(), any());
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject acepta petición sin cuerpo con Content-Type JSON")
    void rejectWithoutBodyWithJsonContentTypeReturnsRejectedTransfer() throws Exception {
        Transfer transfer = new Transfer(Iban.generate(1), Iban.generate(2),
                new BigDecimal("6000.00"), "Alquiler", 2L);
        transfer.rejectByReviewer(99L, null);
        when(transferService.reject(eq(1L), isNull(), any())).thenReturn(transfer);

        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECHAZADA"))
                .andExpect(jsonPath("$.reviewNote").value(nullValue()));

        verify(transferService).reject(eq(1L), isNull(), any());
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject acepta cuerpo JSON vacío {}")
    void rejectWithEmptyJsonObjectReturnsRejectedTransfer() throws Exception {
        Transfer transfer = new Transfer(Iban.generate(1), Iban.generate(2),
                new BigDecimal("6000.00"), "Alquiler", 2L);
        transfer.rejectByReviewer(99L, null);
        when(transferService.reject(eq(1L), isNull(), any())).thenReturn(transfer);

        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECHAZADA"))
                .andExpect(jsonPath("$.reviewNote").value(nullValue()));

        verify(transferService).reject(eq(1L), isNull(), any());
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject acepta comentario en blanco como opcional")
    void rejectWithBlankCommentReturnsRejectedTransfer() throws Exception {
        Transfer transfer = new Transfer(Iban.generate(1), Iban.generate(2),
                new BigDecimal("6000.00"), "Alquiler", 2L);
        transfer.rejectByReviewer(99L, null);
        when(transferService.reject(eq(1L), eq("   "), any())).thenReturn(transfer);

        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"comment":"   "}
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECHAZADA"))
                .andExpect(jsonPath("$.reviewNote").value(nullValue()));

        verify(transferService).reject(eq(1L), eq("   "), any());
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject rechaza comentario mayor a 255 caracteres")
    void rejectWithCommentLongerThan255CharactersReturnsBadRequest() throws Exception {
        String tooLong = "x".repeat(256);

        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"" + tooLong + "\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"))
                .andExpect(jsonPath("$.fields.comment").value("El comentario no puede superar 255 caracteres"));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject rechaza JSON mal formado con 400")
    void rejectWithMalformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject rechaza Content-Type no soportado con 415")
    void rejectWithUnsupportedContentTypeReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("No procede")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("TIPO_CONTENIDO_NO_SOPORTADO"));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject rechaza clientes con cuerpo")
    void rejectWithClienteReturnsForbidden() throws Exception {
        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(2L, "ana@bytebank.test", Role.CLIENTE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"comment":"No procede"}
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject rechaza clientes sin cuerpo")
    void rejectWithoutBodyWithClienteReturnsForbidden() throws Exception {
        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(2L, "ana@bytebank.test", Role.CLIENTE))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESO_DENEGADO"));

        verifyNoInteractions(transferService);
    }

    @Test
    @DisplayName("POST /api/transfers/{id}/reject propaga 409")
    void rejectAlreadyReviewedTransferReturnsConflict() throws Exception {
        when(transferService.reject(eq(1L), eq("No procede"), any()))
                .thenThrow(ApiException.conflict("TRANSFERENCIA_NO_PENDIENTE",
                        "La transferencia 1 ya no está pendiente de revisión"));

        mockMvc.perform(post("/api/transfers/1/reject")
                        .header("Authorization", "Bearer " + token(99L, "gestor@bytebank.test", Role.GESTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"comment":"No procede"}
                                """)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFERENCIA_NO_PENDIENTE"));
    }
}
