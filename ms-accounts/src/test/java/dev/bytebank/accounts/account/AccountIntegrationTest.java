package dev.bytebank.accounts.account;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AccountIntegrationTest extends IntegrationTest {

    @Autowired
    AccountService accountService;

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("BUG-02 (BD Real): AccountService.get() con IBAN inexistente lanza ApiException 404")
    void getNonExistentAccountThrowsNotFoundApiException() {
        String nonExistentIban = "ES9121000418450200051332";

        assertThatThrownBy(() -> accountService.get(nonExistentIban))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
    }

    @Test
    @DisplayName("BUG-02 (HTTP /internal): GET cuenta inexistente devuelve HTTP 404 CUENTA_NO_ENCONTRADA en vez de 500")
    void getNonExistentAccountViaInternalEndpointReturns404() throws Exception {
        String nonExistentIban = "ES9121000418450200051332";

        mockMvc.perform(get("/internal/accounts/" + nonExistentIban)
                        .header("X-Internal-Key", "clave-interna-de-test")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUENTA_NO_ENCONTRADA"));
    }
}
