package dev.bytebank.fraud.check;

import dev.bytebank.common.iban.Iban;
import dev.bytebank.fraud.check.FraudDtos.CheckRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FraudDtosTest {

    private static final String ORIGIN = Iban.generate(1);
    private static final String DESTINATION = Iban.generate(2);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsValidCheckRequest() {
        var req = new CheckRequest(ORIGIN, DESTINATION, 2L, new BigDecimal("19999.99"));
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimals() {
        var req = new CheckRequest(ORIGIN, DESTINATION, 2L, new BigDecimal("19999.999"));
        assertThat(validator.validate(req))
                .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
    }

    @Test
    void rejectsAmountWithMoreThan15IntegerDigits() {
        var req = new CheckRequest(ORIGIN, DESTINATION, 2L, new BigDecimal("1000000000000000.00"));
        assertThat(validator.validate(req))
                .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
    }

    @Test
    void rejectsIbansLongerThan34Characters() {
        String overlong = "ES" + "1".repeat(33);
        assertThat(validator.validate(new CheckRequest(overlong, DESTINATION, 2L, new BigDecimal("10.00"))))
                .anyMatch(v -> v.getPropertyPath().toString().equals("originIban"));
        assertThat(validator.validate(new CheckRequest(ORIGIN, overlong, 2L, new BigDecimal("10.00"))))
                .anyMatch(v -> v.getPropertyPath().toString().equals("destinationIban"));
    }
}
