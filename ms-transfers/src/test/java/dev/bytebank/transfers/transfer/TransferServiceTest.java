package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.AccountsClient.AccountInfo;
import dev.bytebank.transfers.client.FraudClient;
import dev.bytebank.transfers.client.FraudClient.FraudCheckResponse;
import dev.bytebank.transfers.client.FraudClient.Verdict;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    static final String ORIGIN = Iban.generate(1);
    static final String DESTINATION = Iban.generate(2);
    static final String EXTERNAL_DESTINATION = "ES9121000418450200051332";
    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
    static final BigDecimal THRESHOLD = new BigDecimal("5000");

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
        lenient().when(accounts.getAccount(ORIGIN)).thenReturn(new AccountInfo(ORIGIN, "ACTIVA", new BigDecimal("1000"), 2L));
        lenient().when(accounts.getAccount(DESTINATION))
                .thenReturn(new AccountInfo(DESTINATION, "ACTIVA", BigDecimal.ZERO, 3L));
        lenient().when(fraud.check(any()))
                .thenReturn(new FraudCheckResponse(1L, Verdict.APROBADA, List.of()));
    }

    @Test
    void completesTransferBetweenOwnAccounts() {
        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("100.00"), "Cena"), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        assertThat(t.getFraudVerdict()).isEqualTo("APROBADA");
        assertThat(t.getFraudCheckId()).isEqualTo(1L);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("-100.00"), "Cena"), ANA))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IMPORTE_NO_VALIDO"));

        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
    }

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, BigDecimal.ZERO, "Cena"), ANA))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IMPORTE_NO_VALIDO"));

        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
    }

    @Test
    void rejectsWhenOriginHasNoFunds() {
        when(accounts.debit(anyString(), eq(ORIGIN), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "FONDOS_INSUFICIENTES", "Saldo insuficiente"));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("5000.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(t.getRejectionReason()).isEqualTo("FONDOS_INSUFICIENTES");
    }

    @Test
    void rejectsTransferFromAccountOwnedByAnotherUser() {
        when(accounts.getAccount(ORIGIN))
                .thenReturn(new AccountInfo(ORIGIN, "ACTIVA", new BigDecimal("1000"), 99L));

        assertThatThrownBy(() -> service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("ACCESO_DENEGADO");
                });

        verify(transfers, never()).save(any());
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void doesNotDebitWhenDestinationIsBlocked() {
        when(accounts.getAccount(DESTINATION))
                .thenReturn(new AccountInfo(DESTINATION, "BLOQUEADA", BigDecimal.ZERO, 3L));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(t.getRejectionReason()).isEqualTo("CUENTA_NO_OPERATIVA");
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void doesNotDebitWhenDestinationDoesNotExist() {
        when(accounts.getAccount(DESTINATION))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "CUENTA_NO_ENCONTRADA", "No existe la cuenta"));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(t.getRejectionReason()).isEqualTo("CUENTA_NO_ENCONTRADA");
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void refundsOriginWhenCreditFails() {
        when(accounts.credit(anyString(), eq(DESTINATION), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "CUENTA_NO_OPERATIVA", "La cuenta no está activa"));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(t.getRejectionReason()).isEqualTo("CUENTA_NO_OPERATIVA");
        verify(accounts).refund(anyString(), eq(ORIGIN), eq(new BigDecimal("50.00")), anyString());
    }

    @Test
    void marksReversionFailedWhenRefundFails() {
        when(accounts.credit(anyString(), eq(DESTINATION), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "CUENTA_NO_OPERATIVA", "La cuenta no está activa"));
        when(accounts.refund(anyString(), eq(ORIGIN), any(), any()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE", "No responde"));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(t.getRejectionReason()).isEqualTo("REVERSION_FALLIDA");
    }

    @Test
    void cannotTransferToSameAccount() {
        assertThatThrownBy(() -> service.create(new CreateTransferRequest(ORIGIN, ORIGIN, BigDecimal.TEN, null), ANA))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsTransferWhenFraudBlocksIt() {
        when(fraud.check(any()))
                .thenReturn(new FraudCheckResponse(7L, Verdict.BLOQUEADA, List.of("IMPORTE_MAXIMO")));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(t.getRejectionReason()).isEqualTo("FRAUDE_DETECTADO");
        assertThat(t.getFraudVerdict()).isEqualTo("BLOQUEADA");
        assertThat(t.getFraudCheckId()).isEqualTo(7L);
        assertThat(t.getReviewReason()).isNull();
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void marksTransferPendingWhenFraudRequestsReview() {
        when(fraud.check(any()))
                .thenReturn(new FraudCheckResponse(8L, Verdict.REVISAR, List.of("EXTERNA_IMPORTE_ALTO")));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        assertThat(t.getFraudVerdict()).isEqualTo("REVISAR");
        assertThat(t.getFraudCheckId()).isEqualTo(8L);
        assertThat(t.getReviewReason()).isEqualTo("TRANSFERENCIA_EN_REVISION");
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void returnsServiceUnavailableWhenFraudReturnsNoVerdict() {
        when(fraud.check(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("SERVICIO_NO_DISPONIBLE");
                });

        verify(transfers, never()).save(any());
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void returnsServiceUnavailableWhenFraudIsDown() {
        when(fraud.check(any())).thenThrow(new RemoteUnavailableException("ms-fraud no responde"));

        assertThatThrownBy(() -> service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("SERVICIO_NO_DISPONIBLE");
                });

        verify(transfers, never()).save(any());
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void marksTransferPendingWhenAmountExceedsReviewThreshold() {
        Transfer t = service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("5000.01"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        assertThat(t.getReviewReason()).isEqualTo("TRANSFERENCIA_SUPERA_UMBRAL");
        assertThat(t.getFraudVerdict()).isEqualTo("APROBADA");
        assertThat(t.getRejectionReason()).isNull();
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void completesTransferAtReviewThreshold() {
        Transfer t = service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("5000.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        verify(accounts).debit(anyString(), eq(ORIGIN), eq(new BigDecimal("5000.00")), any());
    }

    @Test
    void getReturnsOwnTransfer() {
        Transfer own = new Transfer(ORIGIN, DESTINATION, new BigDecimal("50.00"), null, ANA.id());
        when(transfers.findById(1L)).thenReturn(Optional.of(own));

        Transfer found = service.get(1L, ANA);

        assertThat(found).isSameAs(own);
    }

    @Test
    void getRejectsTransferOwnedByAnotherUser() {
        Transfer other = new Transfer(ORIGIN, DESTINATION, new BigDecimal("50.00"), null, 99L);
        when(transfers.findById(1L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.get(1L, ANA))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("ACCESO_DENEGADO");
                });
    }

    @Test
    void getRejectsWhenTransferDoesNotExist() {
        when(transfers.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(1L, ANA))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("TRANSFERENCIA_NO_ENCONTRADA");
                });
    }

    @Test
    void listReturnsOnlyOwnTransfers() {
        Transfer own = new Transfer(ORIGIN, DESTINATION, new BigDecimal("50.00"), null, ANA.id());
        when(transfers.findByCreatedBy(eq(ANA.id()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(own), PageRequest.of(0, 20), 1));

        PageResponse<TransferResponse> result = service.list(0, 20, ANA);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).status()).isEqualTo(TransferStatus.COMPLETADA);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1);
        verify(transfers).findByCreatedBy(eq(ANA.id()), any(Pageable.class));
    }

    @Test
    void completesTransferToExternalBankWithoutCredit() {
        Transfer t = service.create(
                new CreateTransferRequest(ORIGIN, EXTERNAL_DESTINATION, new BigDecimal("50.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        verify(accounts, never()).getAccount(EXTERNAL_DESTINATION);
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
        verify(accounts).debit(anyString(), eq(ORIGIN), eq(new BigDecimal("50.00")), any());
    }

    @Test
    void marksPendingWhenFraudRequestsReviewWithoutReasons() {
        when(fraud.check(any()))
                .thenReturn(new FraudCheckResponse(8L, Verdict.REVISAR, null));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        assertThat(t.getFraudVerdict()).isEqualTo("REVISAR");
        assertThat(t.getFraudCheckId()).isEqualTo(8L);
        assertThat(t.getReviewReason()).isEqualTo("TRANSFERENCIA_EN_REVISION");
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
    }
}
