package dev.bytebank.notifications.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Evento que publica ms-accounts por cada movimiento. Contrato en docs/contratos/ms-notifications.md.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MovementEvent(
        Long movementId,
        String iban,
        Long ownerId,
        List<Long> holderIds,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String concept,
        String reference,
        Instant createdAt
) {

    public static final String TOPIC = "bytebank.movements";

    /** IDs a los que hay que avisar: titulares sin repetidos o, en eventos antiguos, el ownerId. */
    public List<Long> recipientIds() {
        List<Long> recipients = holderIds == null ? List.of()
                : holderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (recipients.isEmpty()) {
            return ownerId == null ? List.of() : List.of(ownerId);
        }
        return recipients;
    }
}
