package dev.bytebank.accounts.account;

import dev.bytebank.accounts.client.AuthClient;
import dev.bytebank.accounts.client.AuthClient.UserInfo;
import dev.bytebank.accounts.event.MovementPublisher;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final String IBAN = "ES9121000418450200051332";

    @Mock
    AccountRepository accounts;

    @Mock
    MovementRepository movements;

    @Mock
    MovementPublisher publisher;

    @Mock
    AuthClient authClient;

    AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(accounts, movements, publisher, authClient, TransactionOperations.withoutTransaction());
    }

    @Test
    @DisplayName("get() devuelve la cuenta si existe en la base de datos")
    void getReturnsAccountWhenExists() {
        Account account = new Account(IBAN, 1L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(account));

        Account result = service.get(IBAN);

        assertThat(result).isSameAs(account);
    }

    @Test
    @DisplayName("BUG-02: get() lanza ApiException 404 (CUENTA_NO_ENCONTRADA) en vez de 500 cuando la cuenta no existe")
    void getThrowsNotFoundWhenAccountDoesNotExist() {
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(IBAN))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
    }

    @Test
    @DisplayName("SEG-01: getForUser() permite el acceso si el usuario es el titular inicial de la cuenta")
    void getForUserReturnsAccountWhenUserIsOwner() {
        Account account = new Account(IBAN, 1L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(account));

        AuthUser owner = new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE);
        Account result = service.getForUser(IBAN, owner);

        assertThat(result).isSameAs(account);
    }

    @Test
    @DisplayName("CAM-01: getForUser() permite el acceso a cualquier co-titular de la cuenta")
    void getForUserReturnsAccountWhenUserIsCoHolder() {
        Account account = new Account(IBAN, 1L);
        account.addHolder(2L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(account));

        AuthUser coHolder = new AuthUser(2L, "cotitular@bytebank.test", Role.CLIENTE);
        Account result = service.getForUser(IBAN, coHolder);

        assertThat(result).isSameAs(account);
    }

    @Test
    @DisplayName("SEG-01: getForUser() permite el acceso a cualquier cuenta si el usuario es GESTOR")
    void getForUserReturnsAccountWhenUserIsGestor() {
        Account account = new Account(IBAN, 1L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(account));

        AuthUser gestor = new AuthUser(99L, "gestor@bytebank.test", Role.GESTOR);
        Account result = service.getForUser(IBAN, gestor);

        assertThat(result).isSameAs(account);
    }

    @Test
    @DisplayName("SEG-01: getForUser() lanza ApiException 403 (ACCESO_DENEGADO) si un CLIENTE intenta ver una cuenta ajena")
    void getForUserThrowsForbiddenWhenUserIsNotHolderNorGestor() {
        Account account = new Account(IBAN, 1L);
        account.addHolder(3L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(account));

        AuthUser otherUser = new AuthUser(2L, "ajeno@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.getForUser(IBAN, otherUser))
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
        assertThatThrownBy(() -> service.getForUser(IBAN, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(apiEx.getCode()).isEqualTo("NO_AUTENTICADO");
                });
    }

    @Test
    @DisplayName("CAM-01: listOwn() devuelve todas las cuentas donde el usuario es titular (propias y compartidas)")
    void listOwnQueriesAccountsByHolderUserId() {
        Account shared = new Account(IBAN, 1L);
        shared.addHolder(2L);
        when(accounts.findByHolderUserIdOrderByCreatedAt(2L)).thenReturn(List.of(shared));

        List<Account> result = service.listOwn(new AuthUser(2L, "luis@bytebank.test", Role.CLIENTE));

        assertThat(result).containsExactly(shared);
    }

    @Test
    @DisplayName("CAM-01: addHolder() normaliza IBAN, agrega otro cliente y guarda la instancia bloqueada")
    void addHolderNormalizesIbanAndAddsClientByEmail() {
        Account preview = new Account(IBAN, 2L);
        Account locked = new Account(IBAN, 2L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));
        when(authClient.findByEmail("luis@bytebank.test"))
                .thenReturn(new UserInfo(3L, "luis@bytebank.test", "Luis", Role.CLIENTE));
        when(accounts.findByIbanForUpdate(IBAN)).thenReturn(Optional.of(locked));
        when(accounts.save(locked)).thenReturn(locked);

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
        Account updated = service.addHolder("  es91 2100 0418 4502 0005 1332  ", "luis@bytebank.test", ana);

        assertThat(updated).isSameAs(locked);
        assertThat(updated.getHolderIds()).isEqualTo(List.of(2L, 3L));
        verify(accounts).findByIbanForUpdate(IBAN);
        verify(accounts).save(locked);
    }

    @Test
    @DisplayName("CAM-01: cualquier co-titular existente (no solo el creador) puede agregar un nuevo cliente")
    void addHolderAllowsCoHolderToAddAnotherClient() {
        Account preview = new Account(IBAN, 2L);
        preview.addHolder(3L);
        Account locked = new Account(IBAN, 2L);
        locked.addHolder(3L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));
        when(authClient.findByEmail("maria@bytebank.test"))
                .thenReturn(new UserInfo(4L, "maria@bytebank.test", "María", Role.CLIENTE));
        when(accounts.findByIbanForUpdate(IBAN)).thenReturn(Optional.of(locked));
        when(accounts.save(locked)).thenReturn(locked);

        AuthUser luis = new AuthUser(3L, "luis@bytebank.test", Role.CLIENTE);
        Account updated = service.addHolder(IBAN, "maria@bytebank.test", luis);

        assertThat(updated.getHolderIds()).isEqualTo(List.of(2L, 3L, 4L));
    }

    @Test
    @DisplayName("CAM-01: addHolder() rechaza requester null con 401 sin tocar BD ni ms-auth")
    void addHolderRejectsNullRequester() {
        assertThatThrownBy(() -> service.addHolder(IBAN, "luis@bytebank.test", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(apiEx.getCode()).isEqualTo("NO_AUTENTICADO");
                });
        verifyNoInteractions(accounts, authClient);
    }

    @Test
    @DisplayName("CAM-01: addHolder() rechaza cuenta inexistente con 404 sin consultar a ms-auth")
    void addHolderRejectsNonExistentAccountWithoutCallingAuth() {
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.empty());
        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "luis@bytebank.test", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("CAM-01: addHolder() rechaza con 403 a un no titular sin consultar a ms-auth (evita enumeración)")
    void addHolderRejectsNonHolderWithoutCallingAuth() {
        Account preview = new Account(IBAN, 2L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));

        AuthUser intruder = new AuthUser(99L, "ajeno@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "luis@bytebank.test", intruder))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiEx.getCode()).isEqualTo("ACCESO_DENEGADO");
                });
        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("CAM-01: addHolder() rechaza con 403 si quien llama tiene rol GESTOR")
    void addHolderRejectsGestorCaller() {
        AuthUser gestor = new AuthUser(1L, "gestor@bytebank.test", Role.GESTOR);

        assertThatThrownBy(() -> service.addHolder(IBAN, "luis@bytebank.test", gestor))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiEx.getCode()).isEqualTo("ACCESO_DENEGADO");
                });
        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("CAM-01: addHolder() rechaza auto-agregarse con 409 TITULAR_YA_EXISTENTE sin llamar a ms-auth")
    void addHolderRejectsSelfAddBeforeCallingAuth() {
        Account preview = new Account(IBAN, 2L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "  ANA@bytebank.test ", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("TITULAR_YA_EXISTENTE");
                });
        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("CAM-01: addHolder() rechaza cuenta llena (4 titulares) con 409 antes de llamar a ms-auth (anti-enumeración)")
    void addHolderRejectsFullAccountBeforeCallingAuth() {
        Account preview = new Account(IBAN, 2L);
        preview.addHolder(3L);
        preview.addHolder(4L);
        preview.addHolder(5L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "sexto@bytebank.test", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("MAXIMO_TITULARES_ALCANZADO");
                });
        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("CAM-01: addHolder() rechaza con 400 USUARIO_NO_ES_CLIENTE si el email destino pertenece a un GESTOR")
    void addHolderRejectsTargetGestor() {
        Account preview = new Account(IBAN, 2L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));
        when(authClient.findByEmail("gestor@bytebank.test"))
                .thenReturn(new UserInfo(1L, "gestor@bytebank.test", "Gestor", Role.GESTOR));

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "gestor@bytebank.test", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getCode()).isEqualTo("USUARIO_NO_ES_CLIENTE");
                });
        verify(accounts, never()).findByIbanForUpdate(anyString());
        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("CAM-01: addHolder() propaga 404 y 503 de AuthClient sin abrir bloqueo pesimista ni guardar")
    void addHolderPropagatesAuthErrorsWithoutLocking() {
        Account preview = new Account(IBAN, 2L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));
        when(authClient.findByEmail("nadie@bytebank.test"))
                .thenThrow(ApiException.notFound("USUARIO_NO_ENCONTRADO", "No existe"));
        when(authClient.findByEmail("caido@bytebank.test"))
                .thenThrow(new RemoteUnavailableException("No disponible"));

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "nadie@bytebank.test", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("USUARIO_NO_ENCONTRADO"));

        assertThatThrownBy(() -> service.addHolder(IBAN, "caido@bytebank.test", ana))
                .isInstanceOf(RemoteUnavailableException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(accounts, never()).findByIbanForUpdate(anyString());
        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("CAM-01: addHolder() rechaza cuenta bloqueada con 409 CUENTA_NO_OPERATIVA antes de llamar a ms-auth")
    void addHolderRejectsBlockedAccountBeforeCallingAuth() {
        Account preview = new Account(IBAN, 2L);
        preview.changeStatus(AccountStatus.BLOQUEADA);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "luis@bytebank.test", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_OPERATIVA");
                });
        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("CAM-01 (TOCTOU): re-valida bajo FOR UPDATE si la cuenta se bloquea mientras responde ms-auth")
    void addHolderRevalidatesActiveStatusUnderLock() {
        Account preview = new Account(IBAN, 2L);
        Account locked = new Account(IBAN, 2L);
        locked.changeStatus(AccountStatus.BLOQUEADA);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));
        when(authClient.findByEmail("luis@bytebank.test"))
                .thenReturn(new UserInfo(3L, "luis@bytebank.test", "Luis", Role.CLIENTE));
        when(accounts.findByIbanForUpdate(IBAN)).thenReturn(Optional.of(locked));

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "luis@bytebank.test", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_OPERATIVA");
                });
        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("CAM-01 (TOCTOU): re-valida bajo FOR UPDATE si la cuenta se llena (4 titulares) mientras responde ms-auth")
    void addHolderRevalidatesMaxHoldersUnderLock() {
        Account preview = new Account(IBAN, 2L);
        preview.addHolder(3L);
        preview.addHolder(4L);
        Account locked = new Account(IBAN, 2L);
        locked.addHolder(3L);
        locked.addHolder(4L);
        locked.addHolder(5L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));
        when(authClient.findByEmail("sexto@bytebank.test"))
                .thenReturn(new UserInfo(6L, "sexto@bytebank.test", "Sexto", Role.CLIENTE));
        when(accounts.findByIbanForUpdate(IBAN)).thenReturn(Optional.of(locked));

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "sexto@bytebank.test", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("MAXIMO_TITULARES_ALCANZADO");
                });
        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("CAM-01 (TOCTOU): re-valida titularidad del solicitante bajo FOR UPDATE")
    void addHolderRevalidatesRequesterOwnershipUnderLock() {
        Account preview = new Account(IBAN, 2L);
        Account lockedWithoutRequester = new Account(IBAN, 99L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));
        when(authClient.findByEmail("luis@bytebank.test"))
                .thenReturn(new UserInfo(3L, "luis@bytebank.test", "Luis", Role.CLIENTE));
        when(accounts.findByIbanForUpdate(IBAN)).thenReturn(Optional.of(lockedWithoutRequester));

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "luis@bytebank.test", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiEx.getCode()).isEqualTo("ACCESO_DENEGADO");
                });
        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("CAM-01: addHolder() rechaza bajo FOR UPDATE un co-titular ya existente con 409 TITULAR_YA_EXISTENTE")
    void addHolderRejectsExistingCoHolderUnderLock() {
        Account preview = new Account(IBAN, 2L);
        preview.addHolder(3L);
        Account locked = new Account(IBAN, 2L);
        locked.addHolder(3L);
        when(accounts.findByIbanWithHolders(IBAN)).thenReturn(Optional.of(preview));
        when(authClient.findByEmail("luis@bytebank.test"))
                .thenReturn(new UserInfo(3L, "luis@bytebank.test", "Luis", Role.CLIENTE));
        when(accounts.findByIbanForUpdate(IBAN)).thenReturn(Optional.of(locked));

        AuthUser ana = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

        assertThatThrownBy(() -> service.addHolder(IBAN, "luis@bytebank.test", ana))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("TITULAR_YA_EXISTENTE");
                });
        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("changeStatus() modifica el estado de la cuenta exitosamente")
    void changeStatusUpdatesAccountStatus() {
        Account account = new Account(IBAN, 1L);
        when(accounts.findByIbanForUpdate(anyString())).thenReturn(Optional.of(account));

        Account result = service.changeStatus(IBAN, AccountStatus.BLOQUEADA);

        assertThat(result).isSameAs(account);
        assertThat(result.getStatus()).isEqualTo(AccountStatus.BLOQUEADA);
    }

    @Test
    @DisplayName("CON-01: changeStatus() usa el acceso con bloqueo y lanza 404 si la cuenta no existe")
    void changeStatusThrowsNotFoundWhenAccountDoesNotExist() {
        when(accounts.findByIbanForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(IBAN, AccountStatus.BLOQUEADA))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
    }
}
