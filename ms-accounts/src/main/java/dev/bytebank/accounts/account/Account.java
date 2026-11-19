package dev.bytebank.accounts.account;

import dev.bytebank.common.error.ApiException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "accounts")
public class Account {

    public static final int MAX_HOLDERS = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 34)
    private String iban;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "account_holders", joinColumns = @JoinColumn(name = "account_id"))
    @OrderBy("addedAt ASC, userId ASC")
    private Set<AccountHolder> holders = new LinkedHashSet<>();

    protected Account() {
    }

    public Account(String iban, Long ownerId) {
        this.iban = iban;
        this.ownerId = ownerId;
        this.status = AccountStatus.ACTIVA;
        this.balance = BigDecimal.ZERO.setScale(2);
        this.createdAt = Instant.now();
        this.holders.add(new AccountHolder(ownerId, this.createdAt));
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVA;
    }

    public boolean hasHolder(Long userId) {
        if (userId == null) {
            return false;
        }
        return holders.stream().anyMatch(h -> Objects.equals(h.getUserId(), userId));
    }

    public boolean isFull() {
        return holders.size() >= MAX_HOLDERS;
    }

    public void addHolder(Long userId) {
        Objects.requireNonNull(userId, "userId es obligatorio");
        if (!isActive()) {
            throw ApiException.conflict("CUENTA_NO_OPERATIVA", "La cuenta no está activa");
        }
        if (hasHolder(userId)) {
            throw ApiException.conflict("TITULAR_YA_EXISTENTE", "El usuario ya es titular de esta cuenta");
        }
        if (isFull()) {
            throw ApiException.conflict("MAXIMO_TITULARES_ALCANZADO",
                    "Una cuenta no puede tener más de " + MAX_HOLDERS + " titulares");
        }
        holders.add(new AccountHolder(userId, Instant.now()));
    }

    /** Inicializa explícitamente la colección LAZY dentro de la transacción activa (open-in-view: false). */
    public void initializeHolders() {
        holders.size();
    }

    public List<Long> getHolderIds() {
        return holders.stream().map(AccountHolder::getUserId).toList();
    }

    /** ¿Hay saldo suficiente para cargar este importe? */
    public boolean hasFunds(BigDecimal amount) {
        return balance.compareTo(amount) >= 0;
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public void debit(BigDecimal amount) {
        balance = balance.subtract(amount);
    }

    public void changeStatus(AccountStatus status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getIban() {
        return iban;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
