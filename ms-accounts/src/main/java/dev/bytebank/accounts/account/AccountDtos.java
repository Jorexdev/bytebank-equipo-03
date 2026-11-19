package dev.bytebank.accounts.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountResponse(String iban, AccountStatus status, BigDecimal balance, Long ownerId,
                                  List<Long> holderIds, Instant createdAt) {

        public static AccountResponse from(Account account) {
            return new AccountResponse(account.getIban(), account.getStatus(), account.getBalance(),
                    account.getOwnerId(), account.getHolderIds(), account.getCreatedAt());
        }
    }

    public record AddHolderRequest(
            @NotBlank(message = "el email es obligatorio")
            @Size(max = 254, message = "el email no puede superar los 254 caracteres")
            @Email(message = "el email no es válido")
            String email) {
    }

    public record ChangeStatusRequest(@NotNull AccountStatus status) {
    }

    public record DepositRequest(
            @NotNull @DecimalMin(value = "0.01", message = "el importe debe ser positivo")
            @Digits(integer = 15, fraction = 2, message = "como mucho 2 decimales") BigDecimal amount,
            @Size(max = 140) String concept) {
    }
}
