package dev.bytebank.accounts.account;

import dev.bytebank.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final String IBAN = "ES7639990001000000000001";

    @Test
    @DisplayName("CAM-01: una cuenta recién creada registra a su creador como primer titular")
    void registersOwnerAsInitialHolderOnCreation() {
        Account account = new Account(IBAN, 2L);

        assertThat(account.hasHolder(2L)).isTrue();
        assertThat(account.isFull()).isFalse();
        assertThat(account.getHolderIds()).isEqualTo(List.of(2L));
    }

    @Test
    @DisplayName("CAM-01: permite agregar co-titulares hasta alcanzar el máximo de 4 y devuelve lista inmutable")
    void addsCoHoldersUpToFourInOrder() {
        Account account = new Account(IBAN, 2L);

        account.addHolder(3L);
        account.addHolder(4L);
        account.addHolder(5L);

        List<Long> holderIds = account.getHolderIds();
        assertThat(holderIds).isEqualTo(List.of(2L, 3L, 4L, 5L));
        assertThat(account.isFull()).isTrue();
        assertThat(account.hasHolder(2L)).isTrue();
        assertThat(account.hasHolder(3L)).isTrue();
        assertThat(account.hasHolder(4L)).isTrue();
        assertThat(account.hasHolder(5L)).isTrue();
        assertThatThrownBy(() -> holderIds.add(99L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("CAM-01: hasHolder devuelve false para usuarios que no son titulares y para null")
    void hasHolderReturnsFalseForNonHolderAndNull() {
        Account account = new Account(IBAN, 2L);
        account.addHolder(3L);

        assertThat(account.hasHolder(99L)).isFalse();
        assertThat(account.hasHolder(null)).isFalse();
    }

    @Test
    @DisplayName("CAM-01: addHolder(null) rechaza de forma consistente incluso en cuentas llenas o bloqueadas")
    void addHolderRejectsNullConsistently() {
        Account active = new Account(IBAN, 2L);
        assertThatThrownBy(() -> active.addHolder(null)).isInstanceOf(NullPointerException.class);

        active.addHolder(3L);
        active.addHolder(4L);
        active.addHolder(5L);
        assertThatThrownBy(() -> active.addHolder(null)).isInstanceOf(NullPointerException.class);

        active.changeStatus(AccountStatus.BLOQUEADA);
        assertThatThrownBy(() -> active.addHolder(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("CAM-01: rechaza agregar un usuario que ya es titular (incluso con cuenta llena) con 409 TITULAR_YA_EXISTENTE")
    void rejectsAddingExistingHolderEvenWhenFull() {
        Account account = new Account(IBAN, 2L);
        account.addHolder(3L);
        account.addHolder(4L);
        account.addHolder(5L);

        assertThatThrownBy(() -> account.addHolder(2L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("TITULAR_YA_EXISTENTE");
                });

        assertThatThrownBy(() -> account.addHolder(3L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("TITULAR_YA_EXISTENTE");
                });

        assertThat(account.getHolderIds()).isEqualTo(List.of(2L, 3L, 4L, 5L));
    }

    @Test
    @DisplayName("CAM-01: rechaza agregar un quinto titular con 409 MAXIMO_TITULARES_ALCANZADO")
    void rejectsAddingFifthHolder() {
        Account account = new Account(IBAN, 2L);
        account.addHolder(3L);
        account.addHolder(4L);
        account.addHolder(5L);

        assertThatThrownBy(() -> account.addHolder(6L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("MAXIMO_TITULARES_ALCANZADO");
                });

        assertThat(account.getHolderIds()).isEqualTo(List.of(2L, 3L, 4L, 5L));
    }

    @Test
    @DisplayName("CAM-01: rechaza agregar titular cuando la cuenta está BLOQUEADA o CERRADA con 409 CUENTA_NO_OPERATIVA")
    void rejectsAddingHolderWhenAccountIsBlockedOrClosed() {
        Account blocked = new Account(IBAN, 2L);
        blocked.changeStatus(AccountStatus.BLOQUEADA);

        assertThatThrownBy(() -> blocked.addHolder(3L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_OPERATIVA");
                });

        Account closed = new Account(IBAN, 2L);
        closed.changeStatus(AccountStatus.CERRADA);

        assertThatThrownBy(() -> closed.addHolder(3L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_OPERATIVA");
                });
    }

    @Test
    @DisplayName("CAM-01: AccountHolder compara igualdad por userId y valida campos obligatorios")
    void accountHolderEqualityUsesUserIdAndRejectsNulls() {
        AccountHolder first = new AccountHolder(3L, Instant.parse("2026-06-12T10:00:00Z"));
        AccountHolder sameUserLater = new AccountHolder(3L, Instant.parse("2026-06-12T11:00:00Z"));
        AccountHolder otherUser = new AccountHolder(4L, Instant.parse("2026-06-12T10:00:00Z"));

        assertThat(first).isEqualTo(sameUserLater);
        assertThat(first.hashCode()).isEqualTo(sameUserLater.hashCode());
        assertThat(first).isNotEqualTo(otherUser);
        assertThatThrownBy(() -> new AccountHolder(null, Instant.now())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AccountHolder(3L, null)).isInstanceOf(NullPointerException.class);
    }
}
