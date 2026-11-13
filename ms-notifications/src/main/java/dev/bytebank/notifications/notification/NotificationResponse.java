package dev.bytebank.notifications.notification;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Notificación de un movimiento en una cuenta del usuario autenticado")
public record NotificationResponse(
        @Schema(description = "Identificador único de la notificación", example = "1")
        Long id,

        @Schema(description = "IBAN de la cuenta asociada a la notificación", example = "ES7600000000000000000000")
        String iban,

        @Schema(description = "Tipo de notificación (DEPOSITO, RETIRO, TRANSFERENCIA, etc.)", example = "DEPOSITO")
        String type,

        @Schema(description = "Importe del movimiento", example = "150.00")
        BigDecimal amount,

        @Schema(description = "Saldo de la cuenta después del movimiento", example = "1150.00")
        BigDecimal balanceAfter,

        @Schema(description = "Referencia del movimiento (puede ser nula)", example = "REF-001")
        String reference,

        @Schema(description = "Identificador del movimiento asociado", example = "42")
        Long movementId,

        @Schema(description = "Mensaje descriptivo de la notificación", example = "Depósito recibido por 150.00 €")
        String message,

        @Schema(description = "Fecha y hora de creación de la notificación en formato ISO-8601", example = "2026-01-15T10:30:00Z")
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getIban(),
                notification.getType(),
                notification.getAmount(),
                notification.getBalanceAfter(),
                notification.getReference(),
                notification.getMovementId(),
                notification.getMessage(),
                notification.getCreatedAt());
    }
}
