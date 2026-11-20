package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.FraudClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "bytebank.jwt.secret=secreto-de-test-con-al-menos-32-caracteres",
        "bytebank.internal-key=clave-interna-de-test"
})
@Testcontainers
class TransferReviewIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    static final String ORIGIN = Iban.generate(1);
    static final String DESTINATION = Iban.generate(2);
    static final AuthUser GESTOR = new AuthUser(99L, "gestor@bytebank.test", Role.GESTOR);

    @MockBean
    AccountsClient accounts;
    @MockBean
    FraudClient fraud;

    @Autowired
    TransferService service;
    @Autowired
    TransferRepository transfers;
    @Autowired
    PlatformTransactionManager txManager;

    private Transfer pending(String amount) {
        Transfer transfer = new Transfer(ORIGIN, DESTINATION, new BigDecimal(amount), "Alquiler", 2L);
        transfer.pendingReview("TRANSFERENCIA_SUPERA_UMBRAL");
        return transfers.save(transfer);
    }

    @Test
    void approvesPendingTransferAndExecutesTheMovement() {
        Transfer transfer = pending("6000.00");

        Transfer approved = service.approve(transfer.getId(), GESTOR);

        assertThat(approved.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        assertThat(approved.getReviewedBy()).isEqualTo(GESTOR.id());
        assertThat(approved.getReviewedAt()).isNotNull();
        verify(accounts).debit(anyString(), anyString(), any(), any());
        verify(accounts).credit(anyString(), anyString(), any(), any());
    }

    @Test
    void secondApprovalIsRejectedWithConflict() {
        Transfer transfer = pending("6000.00");
        service.approve(transfer.getId(), GESTOR);

        assertThatThrownBy(() -> service.approve(transfer.getId(), GESTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("TRANSFERENCIA_NO_PENDIENTE");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void rejectedTransferStaysRejectedAndDoesNotMoveMoneyAgain() {
        Transfer transfer = pending("6000.00");

        Transfer rejected = service.reject(transfer.getId(), "Sospechosa", GESTOR);

        assertThat(rejected.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(rejected.getReviewNote()).isEqualTo("Sospechosa");
    }

    @Test
    void approveWaitsForTheTransferLock() throws Exception {
        Transfer transfer = pending("6000.00");
        Long id = transfer.getId();

        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                transfers.findByIdForUpdate(id).orElseThrow();
                locked.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<TransferStatus> approver = pool.submit(() -> service.approve(id, GESTOR).getStatus());

            assertThatThrownBy(() -> approver.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();

            assertThat(approver.get(15, TimeUnit.SECONDS)).isEqualTo(TransferStatus.COMPLETADA);
            holder.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}