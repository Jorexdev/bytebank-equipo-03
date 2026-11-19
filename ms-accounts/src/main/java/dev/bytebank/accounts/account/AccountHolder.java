package dev.bytebank.accounts.account;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Instant;
import java.util.Objects;

@Embeddable
public class AccountHolder {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected AccountHolder() {
    }

    public AccountHolder(Long userId, Instant addedAt) {
        this.userId = Objects.requireNonNull(userId, "userId es obligatorio");
        this.addedAt = Objects.requireNonNull(addedAt, "addedAt es obligatorio");
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountHolder that)) {
            return false;
        }
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}
