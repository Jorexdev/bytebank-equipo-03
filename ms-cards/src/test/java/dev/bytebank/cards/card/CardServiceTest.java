package dev.bytebank.cards.card;

import dev.bytebank.cards.client.AccountsClient;
import dev.bytebank.cards.client.AccountsClient.AccountInfo;
import dev.bytebank.cards.payment.CardPayment;
import dev.bytebank.cards.payment.CardPaymentRepository;
import dev.bytebank.cards.payment.PaymentStatus;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class CardServiceTest {

    static final String IBAN = Iban.generate(1);
    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-12T10:00:00Z"), ZoneOffset.UTC);
    static final Instant START_OF_DAY_MADRID = Instant.parse("2026-06-11T22:00:00Z");
    static final BigDecimal LIMIT = new BigDecimal("600.00");

    @Mock
    CardRepository cards;
    @Mock
    CardPaymentRepository payments;
    @Mock
    AccountsClient accountsClient;

    CardService service;

    @BeforeEach
    void setUp() {
        service = new CardService(cards, payments, accountsClient, CLOCK, "Europe/Madrid", LIMIT);
        lenient().when(cards.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(payments.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Emite una tarjeta con IBAN normalizado, last4, límite y fecha del reloj")
    void issuesCard() {
        when(accountsClient.getAccount(IBAN)).thenReturn(new AccountInfo(IBAN, "ACTIVA", new BigDecimal("1000"), ANA.id()));

        Card card = service.issue(" " + IBAN.toLowerCase() + " ", ANA);

        ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);
        verify(cards).save(saved.capture());
        assertThat(saved.getValue().getAccountIban()).isEqualTo(IBAN);
        assertThat(saved.getValue().getUserId()).isEqualTo(ANA.id());
        assertThat(saved.getValue().getLast4()).hasSize(4).containsOnlyDigits();
        assertThat(saved.getValue().getDailyLimit()).isEqualByComparingTo(LIMIT);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(CLOCK.instant());
        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVA);
    }

    @Test
    @DisplayName("Rechaza emitir con IBAN inválido: 400 IBAN_NO_VALIDO")
    void rejectsInvalidIban() {
        assertApiError(() -> service.issue("ES123", ANA), HttpStatus.BAD_REQUEST, "IBAN_NO_VALIDO");
        verifyNoInteractions(accountsClient);
    }

    @Test
    @DisplayName("No emite para una cuenta de otro usuario: 404 CUENTA_NO_ENCONTRADA")
    void rejectsAccountOwnedByAnotherUser() {
        when(accountsClient.getAccount(IBAN)).thenReturn(new AccountInfo(IBAN, "ACTIVA", new BigDecimal("1000"), 99L));

        assertApiError(() -> service.issue(IBAN, ANA), HttpStatus.NOT_FOUND, "CUENTA_NO_ENCONTRADA");
        verify(cards, never()).save(any());
    }

    @Test
    @DisplayName("No emite para una cuenta no activa: 409 CUENTA_NO_OPERATIVA")
    void rejectsInactiveAccount() {
        when(accountsClient.getAccount(IBAN)).thenReturn(new AccountInfo(IBAN, "BLOQUEADA", new BigDecimal("1000"), ANA.id()));

        assertApiError(() -> service.issue(IBAN, ANA), HttpStatus.CONFLICT, "CUENTA_NO_OPERATIVA");
        verify(cards, never()).save(any());
    }

    @Test
    @DisplayName("Aprueba un pago dentro del límite y debita con referencia PAGO_TARJETA")
    void approvesPaymentWithinLimit() {
        Card card = card(1L, ANA.id(), CardStatus.ACTIVA);
        when(cards.findById(1L)).thenReturn(Optional.of(card));
        when(payments.sumAmountSince(1L, PaymentStatus.APROBADO, START_OF_DAY_MADRID)).thenReturn(BigDecimal.ZERO);

        CardPayment result = service.pay(1L, new BigDecimal("42.50"), "Supermercado", ANA);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.APROBADO);
        assertThat(result.getRejectionReason()).isNull();
        ArgumentCaptor<String> reference = ArgumentCaptor.forClass(String.class);
        verify(accountsClient).debit(reference.capture(), eq(IBAN), eq(new BigDecimal("42.50")), eq("Supermercado"));
        assertThat(reference.getValue()).startsWith("CARD-1-");
        verify(payments).save(result);
    }

    @Test
    @DisplayName("Aprueba un pago que deja el gasto justo en el límite diario")
    void approvesPaymentReachingExactLimit() {
        Card card = card(1L, ANA.id(), CardStatus.ACTIVA);
        when(cards.findById(1L)).thenReturn(Optional.of(card));
        when(payments.sumAmountSince(1L, PaymentStatus.APROBADO, START_OF_DAY_MADRID))
                .thenReturn(new BigDecimal("580.00"));

        CardPayment result = service.pay(1L, new BigDecimal("20.00"), "Supermercado", ANA);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.APROBADO);
    }

    @Test
    @DisplayName("Rechaza el pago si la tarjeta está bloqueada, sin debitar")
    void rejectsBlockedCard() {
        Card card = card(1L, ANA.id(), CardStatus.BLOQUEADA);
        when(cards.findById(1L)).thenReturn(Optional.of(card));

        CardPayment result = service.pay(1L, new BigDecimal("10.00"), "Supermercado", ANA);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.RECHAZADO);
        assertThat(result.getRejectionReason()).isEqualTo("TARJETA_BLOQUEADA");
        verifyNoInteractions(accountsClient);
    }

    @Test
    @DisplayName("Rechaza el pago si supera el límite diario, sin debitar")
    void rejectsPaymentOverDailyLimit() {
        Card card = card(1L, ANA.id(), CardStatus.ACTIVA);
        when(cards.findById(1L)).thenReturn(Optional.of(card));
        when(payments.sumAmountSince(1L, PaymentStatus.APROBADO, START_OF_DAY_MADRID))
                .thenReturn(new BigDecimal("590.00"));

        CardPayment result = service.pay(1L, new BigDecimal("20.00"), "Supermercado", ANA);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.RECHAZADO);
        assertThat(result.getRejectionReason()).isEqualTo("LIMITE_DIARIO_SUPERADO");
        verifyNoInteractions(accountsClient);
    }

    @Test
    @DisplayName("Guarda como rechazado el pago si accounts devuelve FONDOS_INSUFICIENTES")
    void rejectsPaymentWithoutFunds() {
        Card card = card(1L, ANA.id(), CardStatus.ACTIVA);
        when(cards.findById(1L)).thenReturn(Optional.of(card));
        when(payments.sumAmountSince(1L, PaymentStatus.APROBADO, START_OF_DAY_MADRID)).thenReturn(BigDecimal.ZERO);
        when(accountsClient.debit(anyString(), eq(IBAN), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "FONDOS_INSUFICIENTES", "Saldo insuficiente"));

        CardPayment result = service.pay(1L, new BigDecimal("10.00"), "Supermercado", ANA);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.RECHAZADO);
        assertThat(result.getRejectionReason()).isEqualTo("FONDOS_INSUFICIENTES");
        verify(payments).save(result);
    }

    @Test
    @DisplayName("Guarda como rechazado el pago si accounts devuelve CUENTA_NO_OPERATIVA")
    void rejectsPaymentOnInactiveAccount() {
        Card card = card(1L, ANA.id(), CardStatus.ACTIVA);
        when(cards.findById(1L)).thenReturn(Optional.of(card));
        when(payments.sumAmountSince(1L, PaymentStatus.APROBADO, START_OF_DAY_MADRID)).thenReturn(BigDecimal.ZERO);
        when(accountsClient.debit(anyString(), eq(IBAN), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "CUENTA_NO_OPERATIVA", "La cuenta no está activa"));

        CardPayment result = service.pay(1L, new BigDecimal("10.00"), "Supermercado", ANA);

        assertThat(result.getRejectionReason()).isEqualTo("CUENTA_NO_OPERATIVA");
    }

    @Test
    @DisplayName("Propaga 503 y no guarda el pago si accounts no responde")
    void propagatesUnavailableAndDoesNotSavePayment() {
        Card card = card(1L, ANA.id(), CardStatus.ACTIVA);
        when(cards.findById(1L)).thenReturn(Optional.of(card));
        when(payments.sumAmountSince(1L, PaymentStatus.APROBADO, START_OF_DAY_MADRID)).thenReturn(BigDecimal.ZERO);
        when(accountsClient.debit(anyString(), eq(IBAN), any(), any()))
                .thenThrow(new RemoteUnavailableException("caído"));

        assertThatThrownBy(() -> service.pay(1L, new BigDecimal("10.00"), "Supermercado", ANA))
                .isInstanceOf(RemoteUnavailableException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getCode()).isEqualTo("SERVICIO_NO_DISPONIBLE");
                });
        verify(payments, never()).save(any());
    }

    @Test
    @DisplayName("No permite pagar con una tarjeta de otro usuario: 404 TARJETA_NO_ENCONTRADA")
    void cannotPayWithAnotherUsersCard() {
        Card card = card(1L, 99L, CardStatus.ACTIVA);
        when(cards.findById(1L)).thenReturn(Optional.of(card));

        assertApiError(() -> service.pay(1L, new BigDecimal("10.00"), "Supermercado", ANA),
                HttpStatus.NOT_FOUND, "TARJETA_NO_ENCONTRADA");
        verifyNoInteractions(accountsClient);
    }

    private static Card card(Long id, Long userId, CardStatus status) {
        Card card = new Card(IBAN, userId, "4821", LIMIT, CLOCK.instant());
        ReflectionTestUtils.setField(card, "id", id);
        card.changeStatus(status);
        return card;
    }

    private static void assertApiError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(status);
                    assertThat(apiEx.getCode()).isEqualTo(code);
                });
    }
}
