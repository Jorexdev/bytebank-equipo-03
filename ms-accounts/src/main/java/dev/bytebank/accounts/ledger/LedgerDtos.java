package dev.bytebank.accounts.ledger;

import dev.bytebank.accounts.movement.MovementType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public final class LedgerDtos {

    private LedgerDtos() {
    }
    public record MovementRequest(
            @NotBlank @Size(max = 64) String reference,
            @NotBlank String iban,
            @NotNull @DecimalMin(value = "0.01", message = "el importe debe ser positivo")
            @Digits(integer = 15, fraction = 2, message = "como mucho 2 decimales") BigDecimal amount,
            @NotNull MovementType type,
            @Size(max = 140) String concept) {
    }

    public record LedgerResult(String reference, BigDecimal balanceAfter) {
    }
}
