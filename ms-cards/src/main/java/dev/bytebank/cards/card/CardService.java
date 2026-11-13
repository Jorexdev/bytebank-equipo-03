package dev.bytebank.cards.card;

import dev.bytebank.cards.client.AccountsClient;
import dev.bytebank.cards.client.AccountsClient.AccountInfo;
import dev.bytebank.cards.payment.CardPayment;
import dev.bytebank.cards.payment.CardPaymentRepository;
import dev.bytebank.cards.payment.PaymentStatus;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class CardService {

    private final CardRepository cards;
    private final CardPaymentRepository payments;
    private final AccountsClient accountsClient;
    private final Clock clock;
    private final ZoneId zone;
    private final BigDecimal defaultDailyLimit;

    public CardService(CardRepository cards, CardPaymentRepository payments, AccountsClient accountsClient, Clock clock,
                       @Value("${bytebank.zone:Europe/Madrid}") String zone,
                       @Value("${bytebank.cards.default-daily-limit}") BigDecimal defaultDailyLimit) {
        this.cards = cards;
        this.payments = payments;
        this.accountsClient = accountsClient;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.defaultDailyLimit = defaultDailyLimit;
    }

    /** Emite una tarjeta de débito para una cuenta del usuario. Ver docs/contratos/ms-cards.md. */
    @Transactional
    public Card issue(String iban, AuthUser user) {
        String normalized = Iban.normalize(iban);
        if (!Iban.isValid(normalized)) {
            throw ApiException.badRequest("IBAN_NO_VALIDO", "El IBAN no es válido");
        }
        AccountInfo account = accountsClient.getAccount(normalized);
        if (!user.id().equals(account.ownerId())) {
            throw accountNotFound(normalized);
        }
        if (!account.isActive()) {
            throw ApiException.conflict("CUENTA_NO_OPERATIVA", "La cuenta " + normalized + " no está activa");
        }
        Card card = new Card(normalized, user.id(), CardNumbers.generateLast4(), defaultDailyLimit, clock.instant());
        return cards.save(card);
    }

    public List<Card> listOwn(AuthUser user) {
        return cards.findByUserIdOrderByCreatedAt(user.id());
    }

    public Card get(Long id, AuthUser user) {
        Card card = cards.findById(id).orElseThrow(() -> notFound(id));
        if (!card.getUserId().equals(user.id())) {
            throw notFound(id);
        }
        return card;
    }

    public Card changeStatus(Long id, CardStatus status, AuthUser user) {
        Card card = get(id, user);
        card.changeStatus(status);
        return cards.save(card);
    }

    /** Cobra un pago con la tarjeta. Ver docs/contratos/ms-cards.md. */
    @Transactional
    public CardPayment pay(Long id, BigDecimal amount, String merchant, AuthUser user) {
        Card card = get(id, user);
        CardPayment payment = new CardPayment(card.getId(), amount, merchant, clock.instant());

        if (!card.isActive()) {
            payment.reject("TARJETA_BLOQUEADA");
            return payments.save(payment);
        }

        BigDecimal spent = payments.sumAmountSince(card.getId(), PaymentStatus.APROBADO, startOfToday());
        if (spent.add(amount).compareTo(card.getDailyLimit()) > 0) {
            payment.reject("LIMITE_DIARIO_SUPERADO");
            return payments.save(payment);
        }

        try {
            accountsClient.debit("CARD-" + card.getId() + "-" + UUID.randomUUID(), card.getAccountIban(), amount, merchant);
        } catch (RemoteUnavailableException e) {
            throw e;
        } catch (ApiException e) {
            payment.reject(e.getCode());
            return payments.save(payment);
        }

        payment.approve();
        return payments.save(payment);
    }

    public List<CardPayment> payments(Long id, AuthUser user) {
        Card card = get(id, user);
        return payments.findByCardIdOrderByCreatedAtDescIdDesc(card.getId());
    }

    private Instant startOfToday() {
        return LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant();
    }

    private static ApiException notFound(Long id) {
        return ApiException.notFound("TARJETA_NO_ENCONTRADA", "No existe la tarjeta " + id);
    }

    private static ApiException accountNotFound(String iban) {
        return ApiException.notFound("CUENTA_NO_ENCONTRADA", "No existe una cuenta " + iban + " del usuario");
    }
}
