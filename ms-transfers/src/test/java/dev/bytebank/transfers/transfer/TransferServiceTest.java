package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.AccountsClient.AccountInfo;
import dev.bytebank.transfers.transfer.TransferDtos.CreateTransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

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
    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

    @Mock
    TransferRepository transfers;
    @Mock
    AccountsClient accounts;

    @InjectMocks
    TransferService service;

    @BeforeEach
    void setUp() {
        lenient().when(transfers.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accounts.getAccount(ORIGIN)).thenReturn(new AccountInfo(ORIGIN, "ACTIVA", new BigDecimal("1000"), 2L));
        lenient().when(accounts.getAccount(DESTINATION))
                .thenReturn(new AccountInfo(DESTINATION, "ACTIVA", BigDecimal.ZERO, 3L));
    }

    @Test
    void completesTransferBetweenOwnAccounts() {
        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("100.00"), "Cena"), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
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
}
