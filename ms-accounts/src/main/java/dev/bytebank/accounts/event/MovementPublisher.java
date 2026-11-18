package dev.bytebank.accounts.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.movement.Movement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publica cada movimiento en Kafka sólo cuando la transacción se ha confirmado en base de datos. */
@Component
public class MovementPublisher {

    private static final Logger log = LoggerFactory.getLogger(MovementPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public MovementPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public void publish(Movement movement, Account account) {
        MovementEvent event = MovementEvent.of(movement, account);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendToKafka(event);
                }
            });
            return;
        }
        sendToKafka(event);
    }

    private void sendToKafka(MovementEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            var future = kafka.send(MovementEvent.TOPIC, event.iban(), payload);
            if (future != null) {
                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Error al enviar a Kafka el movimiento {} de {}", event.movementId(), event.iban(), ex);
                    } else {
                        log.debug("Movimiento {} publicado", event.movementId());
                    }
                });
            } else {
                log.debug("Movimiento {} publicado", event.movementId());
            }
        } catch (JsonProcessingException e) {
            log.error("No se ha podido serializar el movimiento {}", event.movementId(), e);
        } catch (RuntimeException e) {
            log.error("No se ha podido publicar en Kafka el movimiento {} de {}", event.movementId(), event.iban(), e);
        }
    }
}
