package dev.bytebank.cards.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Tarjeta de débito asociada a una cuenta. Solo se guardan los 4 últimos dígitos. */
@Entity
@Table(name = "cards")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_iban", nullable = false, length = 34)
    private String accountIban;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 4)
    private String last4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardStatus status;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Card() {
    }

    public Card(String accountIban, Long userId, String last4, BigDecimal dailyLimit, Instant createdAt) {
        this.accountIban = accountIban;
        this.userId = userId;
        this.last4 = last4;
        this.dailyLimit = dailyLimit;
        this.status = CardStatus.ACTIVA;
        this.createdAt = createdAt;
    }

    public boolean isActive() {
        return status == CardStatus.ACTIVA;
    }

    public void changeStatus(CardStatus status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getAccountIban() {
        return accountIban;
    }

    public Long getUserId() {
        return userId;
    }

    public String getLast4() {
        return last4;
    }

    public CardStatus getStatus() {
        return status;
    }

    public BigDecimal getDailyLimit() {
        return dailyLimit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
