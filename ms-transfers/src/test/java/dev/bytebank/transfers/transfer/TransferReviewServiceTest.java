package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.FraudClient;
import dev.bytebank.transfers.transfer.TransferDtos.CreateTransferRequest;
import dev.bytebank.transfers.transfer.TransferDtos.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferReviewServiceTest {

    static final String ORIGIN = Iban.generate(1);
    static final String DESTINATION = Iban.generate(2);
    static final String EXTERNAL_DESTINATION = "ES9121000418450200051332";
    static final BigDecimal THRESHOLD = new BigDecimal("5000");
    static final AuthUser GESTOR = new AuthUser(99L, "gestor@bytebank.test", Role.GESTOR);
    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

    @Mock
    TransferRepository transfers;
    @Mock
    AccountsClient accounts;
    @Mock
    FraudClient fraud;

    TransferService service;

    @BeforeEach
    void setUp() {
        service = new TransferService(transfers, accounts, fraud, THRESHOLD);
        lenient().when(transfers.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accounts.getAccount(DESTINATION))
                .thenReturn(new AccountsClient.AccountInfo(DESTINATION, "ACTIVA", BigDecimal.ZERO, 3L, null));
    }

    private Transfer pending(String amount) {
        Transfer transfer = new Transfer(ORIGIN, DESTINATION, new BigDecimal(amount), "Alquiler", 2L);
        transfer.pendingReview("TRANSFERENCIA_SUPERA_UMBRAL");
        return transfer;
    }

    @Test
    void approvesPendingTransferAndMovesMoney() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));

        Transfer result = service.approve(1L, GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        assertThat(result.getReviewedBy()).isEqualTo(GESTOR.id());
        assertThat(result.getReviewedAt()).isNotNull();
        verify(accounts).debit(anyString(), eq(ORIGIN), any(), any());
        verify(accounts).credit(anyString(), eq(DESTINATION), any(), any());
    }

    @Test
    void approvalMarksTransferRejectedWhenDebitFails() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));
        when(accounts.debit(anyString(), anyString(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "FONDOS_INSUFICIENTES", "Saldo insuficiente"));

        Transfer result = service.approve(1L, GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(result.getRejectionReason()).isEqualTo("FONDOS_INSUFICIENTES");
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
    }

    @Test
    void approvalKeepsTransferPendingWhenAccountsIsUnavailableBeforeDebit() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));
        when(accounts.debit(anyString(), anyString(), any(), any()))
                .thenThrow(new RemoteUnavailableException("ms-accounts no responde"));

        assertThatThrownBy(() -> service.approve(1L, GESTOR))
                .isInstanceOfSatisfying(RemoteUnavailableException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("SERVICIO_NO_DISPONIBLE");
                });

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        assertThat(transfer.getRejectionReason()).isNull();
        assertThat(transfer.getReviewedBy()).isNull();
        assertThat(transfer.getReviewedAt()).isNull();
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
        verify(transfers, never()).save(any());
    }

    @Test
    void approvalMarksTransferRejectedWhenOriginAccountIsNotOperational() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));
        when(accounts.debit(anyString(), anyString(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "CUENTA_NO_OPERATIVA", "La cuenta no está activa"));

        Transfer result = service.approve(1L, GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(result.getRejectionReason()).isEqualTo("CUENTA_NO_OPERATIVA");
        assertThat(result.getReviewedBy()).isEqualTo(GESTOR.id());
        assertThat(result.getReviewedAt()).isNotNull();
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
    }

    @Test
    void approvalDoesNotCallFraudAgain() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));

        service.approve(1L, GESTOR);

        verifyNoInteractions(fraud);
    }

    @Test
    void createKeepsRejectingWhenAccountsIsUnavailableBeforeDebit() {
        when(accounts.getAccount(ORIGIN))
                .thenReturn(new AccountsClient.AccountInfo(ORIGIN, "ACTIVA", new BigDecimal("1000"), ANA.id(), null));
        when(accounts.getAccount(DESTINATION))
                .thenReturn(new AccountsClient.AccountInfo(DESTINATION, "ACTIVA", BigDecimal.ZERO, 3L, null));
        when(fraud.check(any()))
                .thenReturn(new FraudClient.FraudCheckResponse(1L, FraudClient.Verdict.APROBADA, List.of()));
        when(accounts.debit(anyString(), anyString(), any(), any()))
                .thenThrow(new RemoteUnavailableException("ms-accounts no responde"));

        Transfer result = service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("100.00"), null), ANA);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(result.getRejectionReason()).isEqualTo("SERVICIO_NO_DISPONIBLE");
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
    }

    @Test
    void approvalCompensatesWhenCreditFails() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));
        when(accounts.credit(anyString(), anyString(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "CUENTA_NO_OPERATIVA", "La cuenta no está activa"));

        Transfer result = service.approve(1L, GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(result.getRejectionReason()).isEqualTo("CUENTA_NO_OPERATIVA");
        verify(accounts).refund(eq("TRF-null-REV"), eq(ORIGIN), any(), anyString());
    }

    @Test
    void approvalRejectsWhenDestinationIsNotOperationalWithoutDebiting() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));
        when(accounts.getAccount(DESTINATION))
                .thenReturn(new AccountsClient.AccountInfo(DESTINATION, "BLOQUEADA", BigDecimal.ZERO, 3L, null));

        Transfer result = service.approve(1L, GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(result.getRejectionReason()).isEqualTo("CUENTA_NO_OPERATIVA");
        assertThat(result.getReviewedBy()).isEqualTo(GESTOR.id());
        assertThat(result.getReviewedAt()).isNotNull();
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void approvalRejectsWhenDestinationLookupFails() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));
        when(accounts.getAccount(DESTINATION))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "CUENTA_NO_ENCONTRADA", "No existe la cuenta"));

        Transfer result = service.approve(1L, GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(result.getRejectionReason()).isEqualTo("CUENTA_NO_ENCONTRADA");
        assertThat(result.getReviewedBy()).isEqualTo(GESTOR.id());
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
    }

    @Test
    void approvalKeepsTransferPendingWhenDestinationLookupIsUnavailable() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));
        when(accounts.getAccount(DESTINATION))
                .thenThrow(new RemoteUnavailableException("ms-accounts no responde"));

        assertThatThrownBy(() -> service.approve(1L, GESTOR))
                .isInstanceOf(RemoteUnavailableException.class);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        assertThat(transfer.getRejectionReason()).isNull();
        assertThat(transfer.getReviewedBy()).isNull();
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(transfers, never()).save(any());
    }

    @Test
    void approvalOfExternalTransferOnlyDebits() {
        Transfer transfer = new Transfer(ORIGIN, EXTERNAL_DESTINATION, new BigDecimal("6000.00"), "Alquiler", 2L);
        transfer.pendingReview("TRANSFERENCIA_SUPERA_UMBRAL");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));

        Transfer result = service.approve(1L, GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        verify(accounts).debit(anyString(), eq(ORIGIN), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).getAccount(EXTERNAL_DESTINATION);
    }

    @Test
    void cannotApproveTransferThatIsNotPending() {
        Transfer transfer = new Transfer(ORIGIN, DESTINATION, new BigDecimal("100.00"), null, 2L);
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.approve(1L, GESTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("TRANSFERENCIA_NO_PENDIENTE");
                });
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
    }

    @Test
    void rejectsPendingTransferWithoutMovingMoney() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));

        Transfer result = service.reject(1L, "  Operación sospechosa  ", GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(result.getRejectionReason()).isEqualTo("RECHAZADA_POR_GESTOR");
        assertThat(result.getReviewNote()).isEqualTo("Operación sospechosa");
        assertThat(result.getReviewedBy()).isEqualTo(GESTOR.id());
        assertThat(result.getReviewedAt()).isNotNull();
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void rejectsPendingTransferWithoutComment() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));

        Transfer result = service.reject(1L, null, GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(result.getRejectionReason()).isEqualTo("RECHAZADA_POR_GESTOR");
        assertThat(result.getReviewNote()).isNull();
        assertThat(result.getReviewedBy()).isEqualTo(GESTOR.id());
        assertThat(result.getReviewedAt()).isNotNull();
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void rejectsPendingTransferWithBlankCommentNormalizingToNull() {
        for (String blank : List.of("", "   ", "\t\n", "\u2003")) {
            Transfer transfer = pending("6000.00");
            when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));

            Transfer result = service.reject(1L, blank, GESTOR);

            assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
            assertThat(result.getRejectionReason()).isEqualTo("RECHAZADA_POR_GESTOR");
            assertThat(result.getReviewNote()).isNull();
        }
    }

    @Test
    void acceptsRejectionCommentAtMaximumLength() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));
        String comment = "x".repeat(255);

        Transfer result = service.reject(1L, "  " + comment + "  ", GESTOR);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(result.getReviewNote()).isEqualTo(comment);
    }

    @Test
    void rejectsCommentLongerThan255Characters() {
        assertThatThrownBy(() -> service.reject(1L, "x".repeat(256), GESTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("DATOS_NO_VALIDOS");
                    assertThat(e.getMessage()).isEqualTo("El comentario no puede superar 255 caracteres");
                });
        verify(transfers, never()).findByIdForUpdate(any());
    }

    @Test
    void cannotRejectTransferThatIsNotPending() {
        Transfer transfer = new Transfer(ORIGIN, DESTINATION, new BigDecimal("100.00"), null, 2L);
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.reject(1L, "comentario", GESTOR))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.getCode()).isEqualTo("TRANSFERENCIA_NO_PENDIENTE"));
    }

    @Test
    void reviewFailsWhenTransferDoesNotExist() {
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(1L, GESTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("TRANSFERENCIA_NO_ENCONTRADA");
                });
    }

    @Test
    void approveRequiresAuthenticatedGestor() {
        assertThatThrownBy(() -> service.approve(1L, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getCode()).isEqualTo("NO_AUTENTICADO");
                });
        verify(transfers, never()).findByIdForUpdate(any());
    }

    @Test
    void approveRejectsReviewerWithoutId() {
        assertThatThrownBy(() -> service.approve(1L, new AuthUser(null, "gestor@bytebank.test", Role.GESTOR)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getCode()).isEqualTo("NO_AUTENTICADO");
                });
        verify(transfers, never()).findByIdForUpdate(any());
    }

    @Test
    void approveRejectsClient() {
        assertThatThrownBy(() -> service.approve(1L, ANA))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("ACCESO_DENEGADO");
                });
        verify(transfers, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectRequiresAuthenticatedGestor() {
        assertThatThrownBy(() -> service.reject(1L, "comentario", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getCode()).isEqualTo("NO_AUTENTICADO");
                });
        verify(transfers, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectRejectsClient() {
        assertThatThrownBy(() -> service.reject(1L, "comentario", ANA))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("ACCESO_DENEGADO");
                });
        verify(transfers, never()).findByIdForUpdate(any());
    }

    @Test
    void listPendingReturnsOnlyPendingTransfers() {
        Transfer transfer = pending("6000.00");
        when(transfers.findByStatus(eq(TransferStatus.PENDIENTE_REVISION), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(transfer), PageRequest.of(0, 20), 1));

        PageResponse<TransferResponse> result = service.listPending(0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).status()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        assertThat(result.totalElements()).isEqualTo(1);
    }
}