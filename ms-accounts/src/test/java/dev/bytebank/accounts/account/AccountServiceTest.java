package dev.bytebank.accounts.account;

import dev.bytebank.accounts.event.MovementPublisher;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    AccountRepository accounts;

    @Mock
    MovementRepository movements;

    @Mock
    MovementPublisher publisher;

    @InjectMocks
    AccountService service;

    @Test
    @DisplayName("get() devuelve la cuenta si existe en la base de datos")
    void getReturnsAccountWhenExists() {
        String iban = "ES9121000418450200051332";
        Account account = mock(Account.class);
        when(accounts.findByIban(anyString())).thenReturn(Optional.of(account));

        Account result = service.get(iban);

        assertThat(result).isSameAs(account);
    }

    @Test
    @DisplayName("BUG-02: get() lanza ApiException 404 (CUENTA_NO_ENCONTRADA) en vez de 500 cuando la cuenta no existe")
    void getThrowsNotFoundWhenAccountDoesNotExist() {
        String iban = "ES9121000418450200051332";
        when(accounts.findByIban(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(iban))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
    }

    @Test
    @DisplayName("SEG-01: getForUser() permite el acceso si el usuario es el titular de la cuenta")
    void getForUserReturnsAccountWhenUserIsOwner() {
        String iban = "ES9121000418450200051332";
        Account account = mock(Account.class);
        when(account.getOwnerId()).thenReturn(1L);
        when(accounts.findByIban(anyString())).thenReturn(Optional.of(account));

        AuthUser owner = new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE);
        Account result = service.getForUser(iban, owner);

        assertThat(result).isSameAs(account);
    }

    @Test
    @DisplayName("SEG-01: getForUser() permite el acceso a cualquier cuenta si el usuario es GESTOR")
    void getForUserReturnsAccountWhenUserIsGestor() {
        String iban = "ES9121000418450200051332";
        Account account = mock(Account.class);
        when(accounts.findByIban(anyString())).thenReturn(Optional.of(account));

        AuthUser gestor = new AuthUser(99L, "gestor@bytebank.test", Role.GESTOR);
        Account result = service.getForUser(iban, gestor);

        assertThat(result).isSameAs(account);
    }

    @Test
    @DisplayName("SEG-01: getForUser() lanza ApiException 403 (ACCESO_DENEGADO) si un CLIENTE intenta ver una cuenta ajena")
    void getForUserThrowsForbiddenWhenUserIsNotOwnerNorGestor() {
        String iban = "ES9121000418450200051332";
        Account account = mock(Account.class);
        when(account.getOwnerId()).thenReturn(1L);
        when(accounts.findByIban(anyString())).thenReturn(Optional.of(account));

        AuthUser otherUser = new AuthUser(2L, "ajeno@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.getForUser(iban, otherUser))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiEx.getCode()).isEqualTo("ACCESO_DENEGADO");
                });
    }

    @Test
    @DisplayName("SEG-01: getForUser() lanza ApiException 401 (NO_AUTENTICADO) si el usuario es null")
    void getForUserThrowsUnauthorizedWhenUserIsNull() {
        String iban = "ES9121000418450200051332";

        assertThatThrownBy(() -> service.getForUser(iban, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(apiEx.getCode()).isEqualTo("NO_AUTENTICADO");
                });
    }

    @Test
    @DisplayName("changeStatus() modifica el estado de la cuenta exitosamente")
    void changeStatusUpdatesAccountStatus() {
        String iban = "ES9121000418450200051332";
        Account account = mock(Account.class);
        when(accounts.findByIban(anyString())).thenReturn(Optional.of(account));

        Account result = service.changeStatus(iban, AccountStatus.BLOQUEADA);

        assertThat(result).isSameAs(account);
        verify(account).changeStatus(AccountStatus.BLOQUEADA);
    }
}
