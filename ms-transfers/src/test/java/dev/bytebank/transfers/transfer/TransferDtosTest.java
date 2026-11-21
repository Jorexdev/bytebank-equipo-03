package dev.bytebank.transfers.transfer;

import dev.bytebank.common.iban.Iban;
import dev.bytebank.transfers.transfer.TransferDtos.CreateTransferRequest;
import dev.bytebank.transfers.transfer.TransferDtos.RejectTransferRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

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

    @Test
    void acceptsValidRejectionReason() {
        assertThat(validator.validate(new RejectTransferRequest("Operación no justificada"))).isEmpty();
    }

    @Test
    void rejectsBlankRejectionReason() {
        assertThat(validator.validate(new RejectTransferRequest("   ")))
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("reason");
                    assertThat(v.getMessage()).isEqualTo("El motivo de rechazo es obligatorio");
                });
    }

    @Test
    void rejectsMissingRejectionReason() {
        assertThat(validator.validate(new RejectTransferRequest(null)))
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("reason");
                    assertThat(v.getMessage()).isEqualTo("El motivo de rechazo es obligatorio");
                });
    }

    @Test
    void rejectsRejectionReasonLongerThan255Characters() {
        assertThat(validator.validate(new RejectTransferRequest("x".repeat(256))))
                .anySatisfy(v -> {
                    assertThat(v.getPropertyPath().toString()).isEqualTo("reason");
                    assertThat(v.getMessage()).isEqualTo("El motivo no puede superar 255 caracteres");
                });
    }

    @Test
    void mapsReviewFieldsToTransferResponse() {
        Transfer transfer = new Transfer(Iban.generate(1), Iban.generate(2),
                new BigDecimal("6000.00"), "Alquiler", 2L);
        transfer.pendingReview("TRANSFERENCIA_SUPERA_UMBRAL");
        transfer.recordReview(99L);

        var response = TransferDtos.TransferResponse.from(transfer);

        assertThat(response.reviewReason()).isEqualTo("TRANSFERENCIA_SUPERA_UMBRAL");
        assertThat(response.reviewedBy()).isEqualTo(99L);
        assertThat(response.reviewedAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
        assertThat(response.reviewNote()).isNull();
    }
}
