package dev.bytebank.fraud.rules;

import dev.bytebank.fraud.FraudProperties;
import dev.bytebank.fraud.check.FraudCheckRepository;
import dev.bytebank.fraud.check.Verdict;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/** Bloquea la operación cuando la cuenta supera el número de transferencias permitido en la ventana. */
@Component
public class FrequencyRule implements FraudRule {

    public static final String CODE = "FRECUENCIA_ALTA";

    private final FraudProperties properties;
    private final FraudCheckRepository checks;

    public FrequencyRule(FraudProperties properties, FraudCheckRepository checks) {
        this.properties = properties;
        this.checks = checks;
    }

    @Override
    public Optional<Hit> evaluate(Context context) {
        Instant from = context.now().minus(properties.frequencyWindow());
        long recent = checks.countByOriginIbanAndCreatedAtGreaterThanEqual(context.originIban(), from);
        if (recent >= properties.frequencyLimit()) {
            return Optional.of(new Hit(CODE, Verdict.BLOQUEADA));
        }
        return Optional.empty();
    }
}
