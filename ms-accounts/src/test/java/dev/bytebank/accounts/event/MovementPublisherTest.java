package dev.bytebank.accounts.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.movement.Movement;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.iban.Iban;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovementPublisherTest {

    @Mock
    KafkaTemplate<String, String> kafka;

    ObjectMapper objectMapper;
    MovementPublisher publisher;
    Account account;
    Movement movement;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        publisher = new MovementPublisher(kafka, objectMapper);
        account = new Account(Iban.generate(1), 2L);
        account.credit(new BigDecimal("100.00"));
        movement = new Movement(account, MovementType.INGRESO, new BigDecimal("100.00"), "Ingreso inicial", "REF-1");
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("KAF-02: no envía el evento a Kafka mientras la transacción sigue abierta sin hacer commit")
    void doesNotSendToKafkaBeforeTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publish(movement, account);

        verifyNoInteractions(kafka);
    }

    @Test
    @DisplayName("KAF-02: nunca envía el evento a Kafka si la transacción hace rollback")
    void doesNotSendToKafkaWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publish(movement, account);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(kafka);
    }

    @Test
    @DisplayName("KAF-02: envía el evento a Kafka únicamente después de que la transacción hace commit")
    void sendsToKafkaAfterTransactionCommits() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publish(movement, account);
        verifyNoInteractions(kafka);

        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(MovementEvent.TOPIC), eq(account.getIban()), payloadCaptor.capture());

        MovementEvent sent = objectMapper.readValue(payloadCaptor.getValue(), MovementEvent.class);
        assertThat(sent.iban()).isEqualTo(account.getIban());
        assertThat(sent.ownerId()).isEqualTo(2L);
        assertThat(sent.holderIds()).isEqualTo(List.of(2L));
        assertThat(sent.type()).isEqualTo("INGRESO");
        assertThat(sent.amount()).isEqualByComparingTo("100.00");
        assertThat(sent.balanceAfter()).isEqualByComparingTo("100.00");
        assertThat(sent.concept()).isEqualTo("Ingreso inicial");
        assertThat(sent.reference()).isEqualTo("REF-1");
    }

    @Test
    @DisplayName("KAF-02: si se invoca sin transacción activa, envía el evento inmediatamente (fallback)")
    void sendsImmediatelyWhenNoTransactionIsActive() {
        publisher.publish(movement, account);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(MovementEvent.TOPIC), eq(account.getIban()), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains(account.getIban());
    }

    @Test
    @DisplayName("KAF-02: si la sincronización está inicializada pero no hay transacción real activa, envía inmediatamente")
    void sendsImmediatelyWhenSynchronizationInitializedWithoutActiveTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        publisher.publish(movement, account);

        verify(kafka).send(eq(MovementEvent.TOPIC), eq(account.getIban()), anyString());
    }

    @Test
    @DisplayName("KAF-02: un fallo síncrono de Kafka en afterCommit se registra sin romper la operación ya confirmada")
    void doesNotPropagateExceptionWhenKafkaSendFailsAfterCommit() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Broker Kafka no disponible"));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publish(movement, account);

        assertThatCode(() -> {
            TransactionSynchronizationUtils.triggerAfterCommit();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CAM-01: el evento publicado en Kafka incluye ownerId y todos los holderIds de una cuenta compartida")
    void includesAllAccountHolderIdsInPublishedMovementEvent() throws Exception {
        account.addHolder(3L);
        account.addHolder(4L);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publish(movement, account);
        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(MovementEvent.TOPIC), eq(account.getIban()), payloadCaptor.capture());

        MovementEvent sent = objectMapper.readValue(payloadCaptor.getValue(), MovementEvent.class);
        assertThat(sent.ownerId()).isEqualTo(2L);
        assertThat(sent.holderIds()).isEqualTo(List.of(2L, 3L, 4L));
    }

    @Test
    @DisplayName("CAM-01: el snapshot de holderIds se congela en publish() y no se muta si se agrega un titular antes de afterCommit")
    void capturesImmutableHolderIdsSnapshotBeforeAfterCommit() throws Exception {
        account.addHolder(3L);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publish(movement, account);
        account.addHolder(4L);

        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(MovementEvent.TOPIC), eq(account.getIban()), payloadCaptor.capture());

        MovementEvent sent = objectMapper.readValue(payloadCaptor.getValue(), MovementEvent.class);
        assertThat(sent.holderIds()).isEqualTo(List.of(2L, 3L));
    }
}
