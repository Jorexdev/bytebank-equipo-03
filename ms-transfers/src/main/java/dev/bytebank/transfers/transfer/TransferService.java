package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.FraudClient;
import dev.bytebank.transfers.transfer.TransferDtos.CreateTransferRequest;
import dev.bytebank.transfers.transfer.TransferDtos.TransferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final TransferRepository transfers;
    private final AccountsClient accountsClient;
    private final FraudClient fraudClient;
    private final BigDecimal reviewThreshold;

    public TransferService(TransferRepository transfers, AccountsClient accountsClient, FraudClient fraudClient,
                           @Value("${bytebank.transfers.review-threshold:5000}") BigDecimal reviewThreshold) {
        this.transfers = transfers;
        this.accountsClient = accountsClient;
        this.fraudClient = fraudClient;
        this.reviewThreshold = reviewThreshold;
    }

    public Transfer create(CreateTransferRequest request, AuthUser user) {
        String origin = Iban.normalize(request.originIban());
        String destination = Iban.normalize(request.destinationIban());
        BigDecimal amount = request.amount();

        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("IMPORTE_NO_VALIDO", "El importe debe ser mayor que cero");
        }
        if (!Iban.isValid(destination)) {
            throw ApiException.badRequest("IBAN_NO_VALIDO", "El IBAN de destino no es válido");
        }
        if (origin.equals(destination)) {
            throw ApiException.badRequest("MISMA_CUENTA", "La cuenta de origen y la de destino son la misma");
        }
        AccountsClient.AccountInfo originAccount = accountsClient.getAccount(origin);
        if (originAccount == null || !originAccount.isHolder(user.id())) {
            throw ApiException.forbidden("No puedes transferir desde una cuenta que no es tuya");
        }

        Transfer transfer = new Transfer(origin, destination, amount, request.concept(), user.id());

        if (Iban.isOwnBank(destination)) {
            try {
                AccountsClient.AccountInfo destinationAccount = accountsClient.getAccount(destination);
                if (destinationAccount == null || !destinationAccount.isActive()) {
                    transfer.reject("CUENTA_NO_OPERATIVA");
                    log.info("Transferencia rechazada: CUENTA_NO_OPERATIVA");
                    return transfers.save(transfer);
                }
            } catch (ApiException e) {
                transfer.reject(e.getCode());
                log.info("Transferencia rechazada: {}", e.getCode());
                return transfers.save(transfer);
            }
        }
        FraudClient.FraudCheckResponse fraudCheckResponse =
                fraudClient.check(new FraudClient.FraudCheckRequest(origin, destination, user.id(), amount));

        if (fraudCheckResponse == null || fraudCheckResponse.verdict() == null) {
            log.info("Transferencia al IBAN {} no realizada. Error ms-fraud", transfer.getDestinationIban());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE",
                    "No se pudo realizar la verificación de fraude para la transferencia.");
        }
        transfer.recordFraudCheckResult(fraudCheckResponse.verdict().name(), fraudCheckResponse.checkId());
        if (fraudCheckResponse.verdict() == FraudClient.Verdict.BLOQUEADA) {
            transfer.reject("FRAUDE_DETECTADO");
            log.info("Transferencia al IBAN {} rechazada: FRAUDE_DETECTADO", transfer.getDestinationIban());
            return transfers.save(transfer);
        } else if (fraudCheckResponse.verdict() == FraudClient.Verdict.REVISAR) {
            transfer.pendingReview("TRANSFERENCIA_EN_REVISION");
            log.info("Transferencia al IBAN {} en revisión: {}", transfer.getDestinationIban(), fraudCheckResponse.reasons());
            return transfers.save(transfer);
        }
        if (amount.compareTo(reviewThreshold) > 0) {
            transfer.pendingReview("TRANSFERENCIA_SUPERA_UMBRAL");
            log.info("Transferencia al IBAN {} en revisión: supera el umbral de {}", transfer.getDestinationIban(), reviewThreshold);
            return transfers.save(transfer);
        }

        transfer = transfers.save(transfer);
        try {
            execute(transfer);
        } catch (RemoteUnavailableException e) {
            // create conserva su comportamiento previo: sin conexión con ms-accounts se marca RECHAZADA.
            // La revisión por gestor (approve) sí deja la transferencia en PENDIENTE_REVISION.
            transfer.reject(e.getCode());
            log.info("Transferencia {} rechazada: {}", transfer.getId(), e.getCode());
        }
        return transfers.save(transfer);
    }

    private void execute(Transfer transfer) {
        try {
            accountsClient.debit(transfer.ledgerReference(), transfer.getOriginIban(), transfer.getAmount(),
                    transfer.getConcept());
        } catch (RemoteUnavailableException e) {
            // ms-accounts no respondió antes de debitar: no se ha movido dinero. Quien llama decide
            // si la transferencia queda rechazada (create) o vuelve a PENDIENTE_REVISION por rollback (approve).
            throw e;
        } catch (ApiException e) {
            transfer.reject(e.getCode());
            log.info("Transferencia {} rechazada: {}", transfer.getId(), e.getCode());
            return;
        }

        if (Iban.isOwnBank(transfer.getDestinationIban())) {
            try {
                accountsClient.credit(transfer.ledgerReference(), transfer.getDestinationIban(),
                        transfer.getAmount(), transfer.getConcept());
            } catch (ApiException e) {
                log.warn("No se pudo abonar la transferencia {} en {}: {}",
                        transfer.getId(), transfer.getDestinationIban(), e.getCode());
                compensate(transfer, transfer.getOriginIban(), transfer.getAmount(), e.getCode());
                return;
            }
        }

        transfer.complete();
        log.info("Transferencia {} completada", transfer.getId());
    }

    @Transactional
    public Transfer approve(Long id, AuthUser reviewer) {
        requireGestor(reviewer);
        Transfer transfer = findPendingForUpdate(id);
        // execute puede lanzar RemoteUnavailableException si ms-accounts no responde antes de debitar:
        // al no registrar la revisión ni guardar, el rollback deja la fila en PENDIENTE_REVISION.
        if (!rejectIfDestinationIsNotOperational(transfer)) {
            execute(transfer);
        }
        transfer.recordReview(reviewer.id());
        log.info("Transferencia {} revisada por el gestor {}: {}", id, reviewer.id(), transfer.getStatus());
        return transfers.save(transfer);
    }

    @Transactional
    public Transfer reject(Long id, String reason, AuthUser reviewer) {
        requireGestor(reviewer);
        String note = reason == null ? "" : reason.trim();
        if (note.isEmpty()) {
            throw ApiException.badRequest("DATOS_NO_VALIDOS", "El motivo de rechazo es obligatorio");
        }
        if (note.length() > 255) {
            throw ApiException.badRequest("DATOS_NO_VALIDOS", "El motivo no puede superar 255 caracteres");
        }
        Transfer transfer = findPendingForUpdate(id);
        transfer.rejectByReviewer(reviewer.id(), note);
        log.info("Transferencia {} rechazada por el gestor {}", id, reviewer.id());
        return transfers.save(transfer);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> listPending(int page, int size) {
        Page<Transfer> pending = transfers.findByStatus(TransferStatus.PENDIENTE_REVISION,
                PageRequest.of(page, size, NEWEST_FIRST));
        return PageResponse.of(pending, TransferResponse::from);
    }

    private static void requireGestor(AuthUser reviewer) {
        if (reviewer == null || reviewer.id() == null) {
            throw ApiException.unauthorized("Hace falta un token válido");
        }
        if (!reviewer.isGestor()) {
            throw ApiException.forbidden("Solo un gestor puede revisar transferencias");
        }
    }

    /**
     * Comprueba el destino antes de debitar. Devuelve true si la transferencia ya quedó RECHAZADA.
     * Si ms-accounts no responde, relanza el 503 para que el rollback la deje en PENDIENTE_REVISION.
     * Solo aplica a destinos de ByteBank; los externos no tienen cuenta que consultar.
     */
    private boolean rejectIfDestinationIsNotOperational(Transfer transfer) {
        if (!Iban.isOwnBank(transfer.getDestinationIban())) {
            return false;
        }
        try {
            AccountsClient.AccountInfo destination = accountsClient.getAccount(transfer.getDestinationIban());
            if (destination == null || !destination.isActive()) {
                transfer.reject("CUENTA_NO_OPERATIVA");
                log.info("Transferencia {} rechazada antes de debitar: destino CUENTA_NO_OPERATIVA", transfer.getId());
                return true;
            }
            return false;
        } catch (RemoteUnavailableException e) {
            throw e;
        } catch (ApiException e) {
            transfer.reject(e.getCode());
            log.info("Transferencia {} rechazada antes de debitar: destino {}", transfer.getId(), e.getCode());
            return true;
        }
    }

    private Transfer findPendingForUpdate(Long id) {
        Transfer transfer = transfers.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("TRANSFERENCIA_NO_ENCONTRADA",
                        "No existe la transferencia " + id));
        if (!transfer.isPendingReview()) {
            throw ApiException.conflict("TRANSFERENCIA_NO_PENDIENTE",
                    "La transferencia " + id + " ya no está pendiente de revisión");
        }
        return transfer;
    }

    private void compensate(Transfer transfer, String origin, BigDecimal amount, String rejectionReason) {
        try {
            accountsClient.refund(transfer.ledgerReference() + "-REV", origin, amount,
                    "Reversión transferencia " + transfer.ledgerReference());
            transfer.reject(rejectionReason);
            log.info("Transferencia {} rechazada y reembolsada al origen: {}", transfer.getId(), rejectionReason);
        } catch (ApiException e) {
            transfer.reject("REVERSION_FALLIDA");
            log.error("No se pudo reembolsar la transferencia {} al origen {}: {}",
                    transfer.getId(), origin, e.getCode(), e);
        }
    }

    public Transfer get(Long id, AuthUser user) {
        Transfer transfer = transfers.findById(id)
                .orElseThrow(() -> ApiException.notFound("TRANSFERENCIA_NO_ENCONTRADA", "No existe la transferencia " + id));
        if (Objects.equals(transfer.getCreatedBy(), user.id()) || user.isGestor()) {
            return transfer;
        }

        AccountsClient.AccountInfo originAccount = accountsClient.getAccount(transfer.getOriginIban());
        if (originAccount == null || !originAccount.isHolder(user.id())) {
            throw ApiException.forbidden("No puedes ver esta transferencia");
        }
        return transfer;
    }

    public PageResponse<TransferResponse> list(int page, int size, AuthUser user) {
        Page<Transfer> result = transfers.findByCreatedBy(user.id(), PageRequest.of(page, size, NEWEST_FIRST));
        return PageResponse.of(result, TransferResponse::from);
    }
}
