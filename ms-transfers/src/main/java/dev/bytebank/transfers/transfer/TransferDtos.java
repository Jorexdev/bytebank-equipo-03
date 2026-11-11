package dev.bytebank.transfers.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
            @Positive(message = "El importe debe ser mayor que cero")
            BigDecimal amount,
            @Size(max = 140) String concept) {
    }

    public record TransferResponse(Long id, String originIban, String destinationIban, BigDecimal amount,
                                   String concept, TransferStatus status, String rejectionReason, Instant createdAt) {

        public static TransferResponse from(Transfer t) {
            return new TransferResponse(t.getId(), t.getOriginIban(), t.getDestinationIban(), t.getAmount(),
                    t.getConcept(), t.getStatus(), t.getRejectionReason(), t.getCreatedAt());
        }
    }
}
