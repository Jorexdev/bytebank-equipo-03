package dev.bytebank.accounts.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByIban(String iban);

    @Query("select a from Account a left join fetch a.holders where a.iban = :iban")
    Optional<Account> findByIbanWithHolders(@Param("iban") String iban);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.iban = :iban")
    Optional<Account> findByIbanForUpdate(@Param("iban") String iban);

    @Query("""
            select a from Account a
            join fetch a.holders
            where exists (
                select 1 from Account a2 join a2.holders h
                where a2 = a and h.userId = :userId
            )
            order by a.createdAt asc, a.id asc
            """)
    List<Account> findByHolderUserIdOrderByCreatedAt(@Param("userId") Long userId);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumber();
}
