package dev.bytebank.accounts.account;

import dev.bytebank.accounts.event.MovementPublisher;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.common.error.ApiException;
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
}
