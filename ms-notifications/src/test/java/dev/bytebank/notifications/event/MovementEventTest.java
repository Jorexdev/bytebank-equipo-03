package dev.bytebank.notifications.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MovementEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void eventoSinHolderIdsUsaSoloAlOwner() throws Exception {
        String payload = """
                {
                  "movementId": 154,
                  "iban": "ES7639990001000000000001",
                  "ownerId": 2,
                  "type": "TRANSFERENCIA_EMITIDA",
                  "amount": -50.00,
                  "balanceAfter": 2450.00,
                  "concept": "Alquiler",
                  "reference": "TRF-8f2c",
                  "createdAt": "2026-06-12T10:05:00Z"
                }
                """;

        MovementEvent event = objectMapper.readValue(payload, MovementEvent.class);

        assertThat(event.holderIds()).isNull();
        assertThat(event.recipientIds()).containsExactly(2L);
    }

    @Test
    void eventoConHolderIdsNotificaATodosSinRepetidos() throws Exception {
        String payload = """
                {
                  "movementId": 155,
                  "iban": "ES7639990001000000000001",
                  "ownerId": 2,
                  "holderIds": [2, 3, 3],
                  "type": "INGRESO",
                  "amount": 50.00,
                  "balanceAfter": 2500.00,
                  "concept": "Nómina",
                  "reference": "TRF-9a1b",
                  "createdAt": "2026-06-12T10:05:00Z"
                }
                """;

        MovementEvent event = objectMapper.readValue(payload, MovementEvent.class);

        assertThat(event.recipientIds()).containsExactly(2L, 3L);
    }

    @Test
    void ignoraCamposDesconocidosDelEvento() throws Exception {
        String payload = """
                {
                  "movementId": 156,
                  "iban": "ES7639990001000000000001",
                  "ownerId": 2,
                  "holderIds": [2, 3],
                  "type": "INGRESO",
                  "amount": 50.00,
                  "balanceAfter": 2500.00,
                  "concept": "Nómina",
                  "reference": "TRF-9a1b",
                  "createdAt": "2026-06-12T10:05:00Z",
                  "campoFuturo": "ignorado"
                }
                """;

        MovementEvent event = objectMapper.readValue(payload, MovementEvent.class);

        assertThat(event.movementId()).isEqualTo(156L);
        assertThat(event.recipientIds()).containsExactly(2L, 3L);
    }
}
