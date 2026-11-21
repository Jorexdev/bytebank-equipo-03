package dev.bytebank.transfers.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class TransferDtos {

    private TransferDtos() {
    }

    public record CreateTransferRequest(
            @NotBlank String originIban,
            @NotBlank String destinationIban,
            @NotNull
            @DecimalMin(value = "0.01", message = "El importe debe ser mayor que cero")
            @Digits(integer = 15, fraction = 2, message = "El importe no es válido")
            BigDecimal amount,
            @Size(max = 140) String concept) {
    }

    public record RejectTransferRequest(
            @NotBlank(message = "El motivo de rechazo es obligatorio")
            @Size(max = 255, message = "El motivo no puede superar 255 caracteres")
            String reason) {
    }

    public record TransferResponse(Long id, String originIban, String destinationIban, BigDecimal amount,
                                   String concept, TransferStatus status, String rejectionReason, Instant createdAt,
                                   String reviewReason, Long reviewedBy, Instant reviewedAt, String reviewNote) {

        public static TransferResponse from(Transfer t) {
            return new TransferResponse(t.getId(), t.getOriginIban(), t.getDestinationIban(), t.getAmount(),
                    t.getConcept(), t.getStatus(), t.getRejectionReason(), t.getCreatedAt(),
                    t.getReviewReason(), t.getReviewedBy(), t.getReviewedAt(), t.getReviewNote());
        }
    }
}
