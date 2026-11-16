package dev.bytebank.accounts.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountResponse(String iban, AccountStatus status, BigDecimal balance, Long ownerId, Instant createdAt) {

        public static AccountResponse from(Account account) {
            return new AccountResponse(account.getIban(), account.getStatus(), account.getBalance(),
                    account.getOwnerId(), account.getCreatedAt());
        }
    }

    public record ChangeStatusRequest(@NotNull AccountStatus status) {
    }

    public record DepositRequest(
            @NotNull @DecimalMin(value = "0.01", message = "el importe debe ser positivo")
            @Digits(integer = 15, fraction = 2, message = "como mucho 2 decimales") BigDecimal amount,
            @Size(max = 140) String concept) {
    }
}
