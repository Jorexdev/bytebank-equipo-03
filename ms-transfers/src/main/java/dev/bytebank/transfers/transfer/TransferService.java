package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.transfer.TransferDtos.CreateTransferRequest;
import dev.bytebank.transfers.transfer.TransferDtos.TransferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final TransferRepository transfers;
    private final AccountsClient accountsClient;

    public TransferService(TransferRepository transfers, AccountsClient accountsClient) {
        this.transfers = transfers;
        this.accountsClient = accountsClient;
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
        if (originAccount == null || !Objects.equals(originAccount.ownerId(), user.id())) {
            throw ApiException.forbidden("No puedes transferir desde una cuenta que no es tuya");
        }

        // TODO validar con ms-fraud antes de ejecutar (ver docs/fraude.md)

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

        transfer = transfers.save(transfer);
        try {
            accountsClient.debit(transfer.ledgerReference(), origin, amount, request.concept());
        } catch (ApiException e) {
            transfer.reject(e.getCode());
            log.info("Transferencia {} rechazada: {}", transfer.getId(), e.getCode());
            return transfers.save(transfer);
        }

        if (Iban.isOwnBank(destination)) {
            try {
                accountsClient.credit(transfer.ledgerReference(), destination, amount, request.concept());
            } catch (ApiException e) {
                log.warn("No se pudo abonar la transferencia {} en {}: {}", transfer.getId(), destination, e.getCode());
                compensate(transfer, origin, amount, e.getCode());
                return transfers.save(transfer);
            }
        }
        transfer.complete();
        log.info("Transferencia {} completada", transfer.getId());
        return transfers.save(transfer);
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
        if (!transfer.getCreatedBy().equals(user.id())) {
            throw ApiException.forbidden("No puedes ver esta transferencia");
        }
        return transfer;
    }

    public PageResponse<TransferResponse> list(int page, int size, AuthUser user) {
        var result = transfers.findByCreatedBy(user.id(), PageRequest.of(page, size, NEWEST_FIRST));
        return PageResponse.of(result, TransferResponse::from);
    }
}
