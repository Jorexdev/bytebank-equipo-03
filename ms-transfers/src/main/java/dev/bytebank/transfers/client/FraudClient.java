package dev.bytebank.transfers.client;

import dev.bytebank.common.http.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

@Component
public class FraudClient {
    private final RestClient client;

    public FraudClient(InternalClients clients, @Value("${bytebank.services.fraud-url}") String fraudUrl) {
        this.client = clients.forService(fraudUrl);
    }

    public record FraudCheckRequest(String originIban, String destinationIban, Long userId, BigDecimal amount) {
    }
    public record FraudCheckResponse(Long checkId, Verdict verdict, List<String> reasons) {
    }

    public enum Verdict {
        APROBADA,
        REVISAR,
        BLOQUEADA
    }

    public FraudCheckResponse check(FraudCheckRequest request){
        return InternalClients.call(() -> client.post()
                .uri("/internal/fraud/check")
                .body(request)
                .retrieve()
                .body(FraudCheckResponse.class));
    }

}
