package dev.bytebank.notifications.event;

import java.time.Instant;

/**
 * Evento que publica ms-accounts por cada movimiento. Contrato en docs/contratos/ms-notifications.md.
 */
public record MovementEvent(
        Long movementId,
        String iban,
        Long ownerId,
        String type,
        double amount,
        double balanceAfter,
        String concept,
        String reference,
        Instant createdAt
) {

    public static final String TOPIC = "bytebank.movements";
}
