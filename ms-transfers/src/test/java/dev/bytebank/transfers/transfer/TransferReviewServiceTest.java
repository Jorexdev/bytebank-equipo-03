package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.FraudClient;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferReviewServiceTest {

    static final String ORIGIN = Iban.generate(1);
    static final String DESTINATION = Iban.generate(2);
    static final BigDecimal THRESHOLD = new BigDecimal("5000");
    static final AuthUser GESTOR = new AuthUser(99L, "gestor@bytebank.test", Role.GESTOR);

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
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void rejectRequiresReason() {
        assertThatThrownBy(() -> service.reject(1L, "   ", GESTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("DATOS_NO_VALIDOS");
                });
        verify(transfers, never()).findByIdForUpdate(any());
    }

    @Test
    void cannotRejectTransferThatIsNotPending() {
        Transfer transfer = new Transfer(ORIGIN, DESTINATION, new BigDecimal("100.00"), null, 2L);
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.reject(1L, "motivo", GESTOR))
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