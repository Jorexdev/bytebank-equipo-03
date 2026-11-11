package dev.bytebank.fraud.rules;

import dev.bytebank.fraud.FraudProperties;
import dev.bytebank.fraud.check.FraudCheckRepository;
import dev.bytebank.fraud.check.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrequencyRuleTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String ORIGIN = "ES7639990001000000000001";

    private final FraudCheckRepository checks = mock(FraudCheckRepository.class);
    private final FrequencyRule rule = new FrequencyRule(
            new FraudProperties(new BigDecimal("20000"), new BigDecimal("1000"), 5, Duration.ofMinutes(10)), checks);

    @Test
    void blocksTheSixthTransferWithinTheWindow() {
        when(checks.countByOriginIbanAndCreatedAtGreaterThanEqual(ORIGIN, NOW.minus(Duration.ofMinutes(10))))
                .thenReturn(5L);

        var hit = rule.evaluate(context());

        assertThat(hit).isPresent();
        assertThat(hit.get().code()).isEqualTo(FrequencyRule.CODE);
        assertThat(hit.get().verdict()).isEqualTo(Verdict.BLOQUEADA);
        verify(checks).countByOriginIbanAndCreatedAtGreaterThanEqual(ORIGIN, NOW.minus(Duration.ofMinutes(10)));
    }

    @Test
    void letsTheFifthTransferThrough() {
        when(checks.countByOriginIbanAndCreatedAtGreaterThanEqual(ORIGIN, NOW.minus(Duration.ofMinutes(10))))
                .thenReturn(4L);

        assertThat(rule.evaluate(context())).isEmpty();
    }

    private static FraudRule.Context context() {
        return new FraudRule.Context(ORIGIN, "ES9121000418450200051332", 2L, new BigDecimal("150"), NOW);
    }
}
