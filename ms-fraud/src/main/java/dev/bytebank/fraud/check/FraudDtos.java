package dev.bytebank.fraud.check;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class FraudDtos {

    private FraudDtos() {
    }

    public record CheckRequest(@NotBlank @Size(max = 34) String originIban,
                               @NotBlank @Size(max = 34) String destinationIban,
                               @NotNull Long userId,
                               @NotNull
                               @DecimalMin("0.01")
                               @Digits(integer = 15, fraction = 2)
                               BigDecimal amount) {
    }

    public record CheckResponse(Long checkId, Verdict verdict, List<String> reasons) {
    }

    public record CheckView(Long id, String originIban, Long userId, BigDecimal amount, Verdict verdict,
                            List<String> reasons, Instant createdAt) {

        public static CheckView from(FraudCheck check) {
            return new CheckView(check.getId(), check.getOriginIban(), check.getUserId(), check.getAmount(),
                    check.getVerdict(), check.getReasons(), check.getCreatedAt());
        }
    }
}
