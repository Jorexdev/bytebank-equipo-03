package dev.bytebank.cards.card;

import dev.bytebank.cards.IntegrationTest;
import dev.bytebank.cards.client.AccountsClient;
import dev.bytebank.cards.client.AccountsClient.LedgerResult;
import dev.bytebank.cards.payment.CardPayment;
import dev.bytebank.cards.payment.CardPaymentRepository;
import dev.bytebank.cards.payment.PaymentStatus;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class CardPaymentConcurrencyTest extends IntegrationTest {

    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
    static final BigDecimal LIMIT = new BigDecimal("600.00");
    static final BigDecimal PAYMENT = new BigDecimal("400.00");

    @Autowired
    CardService cardService;

    @Autowired
    CardRepository cards;

    @Autowired
    CardPaymentRepository payments;

    @MockBean
    AccountsClient accountsClient;

    @Test
    @DisplayName("BUG-07: dos pagos concurrentes no pueden superar el límite diario de la tarjeta")
    void concurrentPaymentsDoNotExceedDailyLimit() throws Exception {
        Card card = cards.save(new Card(Iban.generate(1), ANA.id(), "4821", LIMIT, Instant.now()));

        // La latencia del débito amplía la ventana de carrera: sin bloqueo, ambos pagos
        // leerían el mismo gasto del día antes de que el otro confirme.
        when(accountsClient.debit(anyString(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(100);
                    return new LedgerResult("referencia", BigDecimal.ZERO);
                });

        int attempts = 2;
        var ready = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<CardPayment>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    ready.await(5, TimeUnit.SECONDS);
                    return cardService.pay(card.getId(), PAYMENT, "Comercio", ANA);
                }));
            }

            List<CardPayment> results = new ArrayList<>();
            for (Future<CardPayment> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            assertThat(results)
                    .filteredOn(payment -> payment.getStatus() == PaymentStatus.APROBADO)
                    .hasSize(1);
            assertThat(results)
                    .filteredOn(payment -> payment.getStatus() == PaymentStatus.RECHAZADO)
                    .singleElement()
                    .satisfies(payment -> assertThat(payment.getRejectionReason())
                            .isEqualTo("LIMITE_DIARIO_SUPERADO"));
            assertThat(payments.sumAmountSince(card.getId(), PaymentStatus.APROBADO, Instant.EPOCH))
                    .isLessThanOrEqualTo(LIMIT);
        } finally {
            pool.shutdownNow();
        }
    }
}
