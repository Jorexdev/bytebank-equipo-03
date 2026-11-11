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
}
