package dev.bytebank.transfers.transfer;

import dev.bytebank.common.iban.Iban;
import dev.bytebank.transfers.transfer.TransferDtos.CreateTransferRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TransferDtosTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsNegativeAmount() {
        var request = new CreateTransferRequest(Iban.generate(1), Iban.generate(2),
                new BigDecimal("-100.00"), "Cena");

        assertThat(validator.validate(request))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("amount");
                    assertThat(v.getMessage()).isEqualTo("El importe debe ser mayor que cero");
                });
    }

    @Test
    void acceptsPositiveAmount() {
        var request = new CreateTransferRequest(Iban.generate(1), Iban.generate(2),
                new BigDecimal("100.00"), "Cena");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsAmountBelowMinimum() {
        var request = new CreateTransferRequest(Iban.generate(1), Iban.generate(2),
                new BigDecimal("0.001"), "Cena");

        assertThat(validator.validate(request))
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("amount");
                    assertThat(v.getMessage()).isEqualTo("El importe no es válido");
                });
    }

    @Test
    void rejectsAmountWithTooManyIntegerDigits() {
        var request = new CreateTransferRequest(Iban.generate(1), Iban.generate(2),
                new BigDecimal("10000000000000000.00"), "Cena");

        assertThat(validator.validate(request))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("amount");
                    assertThat(v.getMessage()).isEqualTo("El importe no es válido");
                });
    }

    @Test
    void acceptsAmountAtMaximum() {
        var request = new CreateTransferRequest(Iban.generate(1), Iban.generate(2),
                new BigDecimal("999999999999999.99"), "Cena");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void acceptsMinimumAmount() {
        var request = new CreateTransferRequest(Iban.generate(1), Iban.generate(2),
                new BigDecimal("0.01"), "Cena");

        assertThat(validator.validate(request)).isEmpty();
    }
}
