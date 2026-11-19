package dev.bytebank.accounts.account;

import dev.bytebank.accounts.client.AuthClient;
import dev.bytebank.accounts.client.AuthClient.UserInfo;
import dev.bytebank.accounts.event.MovementPublisher;
import dev.bytebank.accounts.movement.Movement;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final MovementRepository movements;
    private final MovementPublisher publisher;
    private final AuthClient authClient;
    private final TransactionOperations transactionOperations;

    public AccountService(AccountRepository accounts, MovementRepository movements, MovementPublisher publisher,
                          AuthClient authClient, TransactionOperations transactionOperations) {
        this.accounts = accounts;
        this.movements = movements;
        this.publisher = publisher;
        this.authClient = authClient;
        this.transactionOperations = transactionOperations;
    }

    @Transactional
    public Account open(AuthUser user) {
        if (user == null || user.id() == null) {
            throw ApiException.unauthorized("Hace falta un token válido");
        }
        Account account = accounts.save(new Account(Iban.generate(accounts.nextAccountNumber()), user.id()));
        log.info("Cuenta {} abierta por el usuario {}", maskIban(account.getIban()), user.id());
        return account;
    }

    @Transactional(readOnly = true)
    public List<Account> listOwn(AuthUser user) {
        if (user == null || user.id() == null) {
            throw ApiException.unauthorized("Hace falta un token válido");
        }
        return accounts.findByHolderUserIdOrderByCreatedAt(user.id());
    }

    @Transactional(readOnly = true)
    public Account get(String iban) {
        return accounts.findByIbanWithHolders(Iban.normalize(iban))
                .orElseThrow(() -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "Cuenta no encontrada"));
    }

    /** Valida que el usuario sea titular de la cuenta o tenga rol GESTOR. */
    @Transactional(readOnly = true)
    public Account getForUser(String iban, AuthUser user) {
        if (user == null || user.id() == null) {
            throw ApiException.unauthorized("Hace falta un token válido");
        }
        Account account = get(iban);
        if (!user.isGestor() && !account.hasHolder(user.id())) {
            log.warn("Acceso denegado a cuenta {} para usuario {}", maskIban(account.getIban()), user.id());
            throw ApiException.forbidden("No tienes permiso para ver esta cuenta");
        }
        return account;
    }

    /**
     * Agrega otro cliente como co-titular de la cuenta (máximo 4 titulares).
     * Resuelve el usuario en ms-auth fuera de la transacción/bloqueo pesimista para no retener locks en BD.
     */
    public Account addHolder(String iban, String email, AuthUser requester) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("addHolder no debe ejecutarse dentro de una transacción activa");
        }
        if (requester == null || requester.id() == null) {
            throw ApiException.unauthorized("Hace falta un token válido");
        }
        if (requester.role() != Role.CLIENTE) {
            log.warn("Intento de añadir titular con rol {} por usuario {}", requester.role(), requester.id());
            throw ApiException.forbidden("Solo un cliente titular puede añadir co-titulares");
        }
        String normalizedIban = Iban.normalize(iban);
        String normalizedEmail = email == null ? "" : email.trim();

        Account preview = accounts.findByIbanWithHolders(normalizedIban)
                .orElseThrow(() -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "Cuenta no encontrada"));
        if (!preview.hasHolder(requester.id())) {
            log.warn("Intento de añadir titular en cuenta {} por usuario no titular {}",
                    maskIban(normalizedIban), requester.id());
            throw ApiException.forbidden("No eres titular de esta cuenta");
        }
        if (!preview.isActive()) {
            throw ApiException.conflict("CUENTA_NO_OPERATIVA", "La cuenta no está activa");
        }
        if (requester.email() != null && requester.email().equalsIgnoreCase(normalizedEmail)) {
            throw ApiException.conflict("TITULAR_YA_EXISTENTE", "El usuario ya es titular de esta cuenta");
        }
        if (preview.isFull()) {
            throw ApiException.conflict("MAXIMO_TITULARES_ALCANZADO",
                    "Una cuenta no puede tener más de " + Account.MAX_HOLDERS + " titulares");
        }

        UserInfo targetUser = authClient.findByEmail(normalizedEmail);
        if (!targetUser.isCliente()) {
            throw ApiException.badRequest("USUARIO_NO_ES_CLIENTE",
                    "Solo se pueden añadir clientes como titulares de una cuenta");
        }

        return transactionOperations.execute(status -> {
            Account locked = accounts.findByIbanForUpdate(normalizedIban)
                    .orElseThrow(() -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "Cuenta no encontrada"));
            if (!locked.hasHolder(requester.id())) {
                throw ApiException.forbidden("No eres titular de esta cuenta");
            }
            locked.addHolder(targetUser.id());
            Account saved = accounts.save(locked);
            log.info("Titular {} añadido a la cuenta {} por el usuario {}",
                    targetUser.id(), maskIban(saved.getIban()), requester.id());
            return saved;
        });
    }

    @Transactional
    @PreAuthorize("hasRole('GESTOR')")
    public Account changeStatus(String iban, AccountStatus status) {
        Account account = accounts.findByIbanForUpdate(Iban.normalize(iban))
                .orElseThrow(() -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "Cuenta no encontrada"));
        account.changeStatus(status);
        account.initializeHolders();
        log.info("Cuenta {} pasa a {}", maskIban(account.getIban()), status);
        return account;
    }

    @Transactional
    public Account deposit(String iban, BigDecimal amount, String concept) {
        // Se bloquea ingreso simultáneo a un cargo.
        Account account = accounts.findByIbanForUpdate(Iban.normalize(iban))
                .orElseThrow(() -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "Cuenta no encontrada"));
        if (!account.isActive()) {
            throw ApiException.conflict("CUENTA_NO_OPERATIVA", "La cuenta no está activa");
        }
        account.credit(amount);
        account.initializeHolders();
        Movement movement = movements.save(new Movement(account, MovementType.INGRESO, amount,
                concept == null || concept.isBlank() ? "Ingreso" : concept, null));
        publisher.publish(movement, account);
        return account;
    }

    private static String maskIban(String iban) {
        if (iban == null || iban.length() < 8) {
            return "****";
        }
        return iban.substring(0, 4) + "****" + iban.substring(iban.length() - 4);
    }
}
