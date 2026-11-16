package dev.bytebank.accounts.account;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AccountIntegrationTest extends IntegrationTest {

    @Autowired
    AccountService accountService;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("BUG-02 (BD Real): AccountService.get() con IBAN inexistente lanza ApiException 404")
    void getNonExistentAccountThrowsNotFoundApiException() {
        String nonExistentIban = "ES9121000418450200051332";

        assertThatThrownBy(() -> accountService.get(nonExistentIban))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
    }

    @Test
    @DisplayName("BUG-02 (HTTP /internal): GET cuenta inexistente devuelve HTTP 404 CUENTA_NO_ENCONTRADA en vez de 500")
    void getNonExistentAccountViaInternalEndpointReturns404() throws Exception {
        String nonExistentIban = "ES9121000418450200051332";
        mockMvc.perform(get("/internal/accounts/" + nonExistentIban)
                        .header("X-Internal-Key", "clave-interna-de-test")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUENTA_NO_ENCONTRADA"));
    }

    @Test
    @WithMockUser(roles = "GESTOR")
    @DisplayName("CON-01: changeStatus() se bloquea si otra transacción sujeta la cuenta")
    void changeStatusWaitsForTheAccountLock() throws Exception {
        Account account = new Account(Iban.generate(accounts.nextAccountNumber()), 1L);
        account.credit(new BigDecimal("100.00"));
        String iban = accounts.save(account).getIban();

        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = new DelegatingSecurityContextExecutorService(Executors.newFixedThreadPool(2));
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                accounts.findByIbanForUpdate(iban).orElseThrow();
                locked.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<AccountStatus> changer =
                    pool.submit(() -> accountService.changeStatus(iban, AccountStatus.BLOQUEADA).getStatus());

            assertThatThrownBy(() -> changer.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();

            assertThat(changer.get(15, TimeUnit.SECONDS)).isEqualTo(AccountStatus.BLOQUEADA);
            holder.get(15, TimeUnit.SECONDS);

            Account reloaded = accounts.findByIban(iban).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.BLOQUEADA);
            assertThat(reloaded.getBalance()).isEqualByComparingTo("100.00");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
