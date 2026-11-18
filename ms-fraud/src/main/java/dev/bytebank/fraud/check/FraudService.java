package dev.bytebank.fraud.check;

import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.fraud.check.FraudDtos.CheckRequest;
import dev.bytebank.fraud.check.FraudDtos.CheckResponse;
import dev.bytebank.fraud.check.FraudDtos.CheckView;
import dev.bytebank.fraud.rules.FraudRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);

    private final List<FraudRule> rules;
    private final FraudCheckRepository checks;
    private final Clock clock;

    public FraudService(List<FraudRule> rules, FraudCheckRepository checks, Clock clock) {
        this.rules = rules;
        this.checks = checks;
        this.clock = clock;
    }

    /** Pasa todas las reglas y se queda con el veredicto más grave. */
    @Transactional
    public CheckResponse check(CheckRequest request) {
        Instant now = clock.instant();
        String iban = Iban.normalize(request.originIban());
        var context = new FraudRule.Context(iban, Iban.normalize(request.destinationIban()), request.userId(),
                request.amount(), now);

        Verdict verdict = Verdict.APROBADA;
        List<String> reasons = new ArrayList<>();
        for (FraudRule rule : rules) {
            var hit = rule.evaluate(context);
            if (hit.isPresent()) {
                reasons.add(hit.get().code());
                verdict = verdict.worst(hit.get().verdict());
            }
        }
        FraudCheck saved = checks.save(new FraudCheck(iban, request.userId(), request.amount(), verdict, reasons, now));
        if (verdict != Verdict.APROBADA) {
            log.info("Validación {} de {}: {} {}", saved.getId(), iban, verdict, reasons);
        }
        return new CheckResponse(saved.getId(), verdict, List.copyOf(reasons));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('GESTOR')")
    public PageResponse<CheckView> list(String iban, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = iban == null ? checks.findAll(pageable) : checks.findByOriginIban(Iban.normalize(iban), pageable);
        return PageResponse.of(result, CheckView::from);
    }
}
