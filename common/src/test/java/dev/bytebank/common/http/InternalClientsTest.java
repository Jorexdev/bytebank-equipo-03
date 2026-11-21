package dev.bytebank.common.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalClientsTest {

    @Test
    void sanitizesInternalErrorDetailsOnRemoteUnavailable() {
        assertThatThrownBy(() -> InternalClients.call(() -> {
            throw new ResourceAccessException("I/O error on GET http://ms-accounts:8082/internal/accounts/ES7639990001000000000001");
        }))
                .isInstanceOfSatisfying(RemoteUnavailableException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getCode()).isEqualTo("SERVICIO_NO_DISPONIBLE");
                    assertThat(ex.getMessage())
                            .doesNotContain("ms-accounts")
                            .doesNotContain("8082")
                            .doesNotContain("/internal/");
                });
    }
}
