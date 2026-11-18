package dev.bytebank.accounts.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.account.AccountRepository;
import dev.bytebank.accounts.account.AccountService;
import dev.bytebank.accounts.account.AccountStatus;
import dev.bytebank.accounts.ledger.LedgerDtos.MovementRequest;
import dev.bytebank.accounts.ledger.LedgerService;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MovementPublisherIntegrationTest extends IntegrationTest {

    @Autowired
    LedgerService ledgerService;

    @Autowired
    AccountService accountService;

    @Autowired
    AccountRepository accounts;

    @Autowired
    MovementRepository movements;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    String originIban;
    String blockedDestinationIban;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");

        Account origin = new Account(Iban.generate(accounts.nextAccountNumber()), 2L);
        origin.credit(new BigDecimal("100.00"));
        originIban = accounts.save(origin).getIban();

        Account destination = new Account(Iban.generate(accounts.nextAccountNumber()), 3L);
        destination.changeStatus(AccountStatus.BLOQUEADA);
        blockedDestinationIban = accounts.save(destination).getIban();
    }

    @Test
    @DisplayName("KAF-02 (BD + Kafka Real): si una operación falla después de publicar y hace rollback, no se envía aviso a Kafka")
    void doesNotPublishEventToKafkaWhenTransactionRollsBackAfterPublish() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        try (KafkaConsumer<String, String> consumer = createConsumer()) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                accountService.deposit(originIban, new BigDecimal("50.00"), "Ingreso que fallará");
                throw new IllegalStateException("Fallo simulado antes del commit");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(accounts.findByIban(originIban).orElseThrow().getBalance())
                    .isEqualByComparingTo("100.00");
            assertThat(movements.count()).isZero();

            List<MovementEvent> events = pollEventsForIban(consumer, Set.of(originIban), Duration.ofMillis(1200), false);
            assertThat(events).isEmpty();
        }
    }

    @Test
    @DisplayName("KAF-02 (Transferencia atómica fallida): si el cargo funciona pero el abono falla en la misma transacción, no sale aviso del cargo")
    void doesNotPublishDebitEventWhenAtomicTransferRollsBackOnBlockedDestination() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        try (KafkaConsumer<String, String> consumer = createConsumer()) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                ledgerService.debit(new MovementRequest(
                        "TRF-ATOM-1", originIban, new BigDecimal("40.00"),
                        MovementType.TRANSFERENCIA_EMITIDA, "Alquiler"));
                ledgerService.credit(new MovementRequest(
                        "TRF-ATOM-1", blockedDestinationIban, new BigDecimal("40.00"),
                        MovementType.TRANSFERENCIA_RECIBIDA, "Alquiler"));
            })).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getCode()).isEqualTo("CUENTA_NO_OPERATIVA"));

            assertThat(accounts.findByIban(originIban).orElseThrow().getBalance())
                    .isEqualByComparingTo("100.00");
            assertThat(movements.count()).isZero();

            List<MovementEvent> events = pollEventsForIban(
                    consumer, Set.of(originIban, blockedDestinationIban), Duration.ofMillis(1200), false);
            assertThat(events).isEmpty();
        }
    }

    @Test
    @DisplayName("KAF-02 (BD + Kafka Real): cuando la operación hace commit, sí se publica el evento en Kafka")
    void publishesEventToKafkaAfterTransactionCommits() {
        try (KafkaConsumer<String, String> consumer = createConsumer()) {
            ledgerService.debit(new MovementRequest(
                    "TRF-OK-1", originIban, new BigDecimal("25.00"),
                    MovementType.TRANSFERENCIA_EMITIDA, "Compra"));

            assertThat(accounts.findByIban(originIban).orElseThrow().getBalance())
                    .isEqualByComparingTo("75.00");
            var savedMovements = movements.findAll();
            assertThat(savedMovements).hasSize(1);
            var savedMovement = savedMovements.get(0);

            List<MovementEvent> events = pollEventsForIban(consumer, Set.of(originIban), Duration.ofSeconds(5), true);
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.movementId()).isEqualTo(savedMovement.getId());
                assertThat(event.iban()).isEqualTo(originIban);
                assertThat(event.ownerId()).isEqualTo(2L);
                assertThat(event.type()).isEqualTo("TRANSFERENCIA_EMITIDA");
                assertThat(event.amount()).isEqualByComparingTo("-25.00");
                assertThat(event.balanceAfter()).isEqualByComparingTo("75.00");
                assertThat(event.concept()).isEqualTo("Compra");
                assertThat(event.reference()).isEqualTo("TRF-OK-1");
                assertThat(event.createdAt()).isNotNull();
            });
        }
    }

    private KafkaConsumer<String, String> createConsumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnectionDetails.getConsumerBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "kaf02-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(MovementEvent.TOPIC));
        return consumer;
    }

    private List<MovementEvent> pollEventsForIban(KafkaConsumer<String, String> consumer,
                                                  Set<String> ibans,
                                                  Duration timeout,
                                                  boolean stopOnFirstMatch) {
        List<MovementEvent> matched = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            var records = consumer.poll(Duration.ofMillis(200));
            for (ConsumerRecord<String, String> record : records) {
                if (ibans.contains(record.key())) {
                    try {
                        matched.add(objectMapper.readValue(record.value(), MovementEvent.class));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            if (!matched.isEmpty() && stopOnFirstMatch) {
                break;
            }
        }
        return matched;
    }
}
