package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.FraudClient;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.concurrent.Callable;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @BeforeEach
    void setUp() {
        lenient().when(accounts.getAccount(DESTINATION))
                .thenReturn(new AccountsClient.AccountInfo(DESTINATION, "ACTIVA", BigDecimal.ZERO, 3L, null));
    }

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
    void approveRollsBackToPendingReviewWhenAccountsIsUnavailableBeforeDebit() {
        Transfer transfer = pending("6000.00");
        when(accounts.debit(anyString(), anyString(), any(), any()))
                .thenThrow(new RemoteUnavailableException("ms-accounts no responde"));

        assertThatThrownBy(() -> service.approve(transfer.getId(), GESTOR))
                .isInstanceOf(RemoteUnavailableException.class);

        Transfer reloaded = transfers.findById(transfer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        assertThat(reloaded.getRejectionReason()).isNull();
        assertThat(reloaded.getReviewedBy()).isNull();
        assertThat(reloaded.getReviewedAt()).isNull();
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
    }

    @Test
    void approveRejectsWhenDestinationWasBlockedWhilePending() {
        Transfer transfer = pending("6000.00");
        when(accounts.getAccount(DESTINATION))
                .thenReturn(new AccountsClient.AccountInfo(DESTINATION, "BLOQUEADA", BigDecimal.ZERO, 3L, null));

        Transfer rejected = service.approve(transfer.getId(), GESTOR);

        assertThat(rejected.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(rejected.getRejectionReason()).isEqualTo("CUENTA_NO_OPERATIVA");
        assertThat(rejected.getReviewedBy()).isEqualTo(GESTOR.id());
        assertThat(rejected.getReviewedAt()).isNotNull();
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
        verify(accounts, never()).credit(anyString(), anyString(), any(), any());
        verify(accounts, never()).refund(anyString(), anyString(), any(), any());
    }

    @Test
    void onlyOneConcurrentApprovalExecutesTheMovement() throws Exception {
        Transfer transfer = pending("6000.00");
        Long id = transfer.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<String> attempt = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return service.approve(id, GESTOR).getStatus().name();
                } catch (ApiException e) {
                    return e.getCode();
                }
            };
            Future<String> first = pool.submit(attempt);
            Future<String> second = pool.submit(attempt);

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMPLETADA", "TRANSFERENCIA_NO_PENDIENTE");
            verify(accounts, times(1)).debit(anyString(), anyString(), any(), any());
        } finally {
            pool.shutdownNow();
        }
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
    void rejectedTransferWithoutCommentPersistsNullReviewNote() {
        Transfer transfer = pending("6000.00");

        service.reject(transfer.getId(), null, GESTOR);

        Transfer persisted = transfers.findById(transfer.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(persisted.getRejectionReason()).isEqualTo("RECHAZADA_POR_GESTOR");
        assertThat(persisted.getReviewNote()).isNull();
        assertThat(persisted.getReviewedBy()).isEqualTo(GESTOR.id());
        assertThat(persisted.getReviewedAt()).isNotNull();
        verify(accounts, never()).debit(anyString(), anyString(), any(), any());
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