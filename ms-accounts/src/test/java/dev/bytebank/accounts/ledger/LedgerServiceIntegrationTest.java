package dev.bytebank.accounts.ledger;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.account.AccountRepository;
import dev.bytebank.accounts.account.AccountStatus;
import dev.bytebank.accounts.ledger.LedgerDtos.MovementRequest;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceIntegrationTest extends IntegrationTest {

    @Autowired
    LedgerService ledger;
    @Autowired
    AccountRepository accounts;
    @Autowired
    JdbcTemplate jdbc;

    String iban;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");
        Account account = new Account(Iban.generate(accounts.nextAccountNumber()), 1L);
        account.credit(new BigDecimal("100.00"));
        iban = accounts.save(account).getIban();
    }

    private BigDecimal balance() {
        return accounts.findByIban(iban).orElseThrow().getBalance();
    }

    private static MovementRequest debit(String reference, String iban, String amount) {
        return new MovementRequest(reference, iban, new BigDecimal(amount), MovementType.TRANSFERENCIA_EMITIDA, null);
    }

    private static MovementRequest credit(String reference, String iban, String amount) {
        return new MovementRequest(reference, iban, new BigDecimal(amount), MovementType.TRANSFERENCIA_RECIBIDA, null);
    }

    @Test
    void debitReducesBalance() {
        ledger.debit(debit("TRF-1", iban, "30.00"));

        assertThat(balance()).isEqualByComparingTo("70.00");
    }

    @Test
    void creditIncreasesBalance() {
        ledger.credit(credit("TRF-2", iban, "50.00"));

        assertThat(balance()).isEqualByComparingTo("150.00");
    }

    @Test
    void cannotDebitMoreThanBalance() {
        assertThatThrownBy(() -> ledger.debit(debit("TRF-3", iban, "100.01")))
                .isInstanceOf(ApiException.class);
        assertThat(balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void cannotCreditBlockedAccount() {
        Account account = accounts.findByIban(iban).orElseThrow();
        account.changeStatus(AccountStatus.BLOQUEADA);
        accounts.save(account);

        assertThatThrownBy(() -> ledger.credit(credit("TRF-4", iban, "10.00")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void moneyIsExactToTheCent() {
        ledger.credit(credit("TRF-5", iban, "0.10"));
        ledger.credit(credit("TRF-6", iban, "0.20"));

        assertThat(balance()).isEqualByComparingTo("100.30");
    }
}
