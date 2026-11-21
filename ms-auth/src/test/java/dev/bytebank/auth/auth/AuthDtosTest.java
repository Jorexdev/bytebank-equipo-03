package dev.bytebank.auth.auth;

import dev.bytebank.auth.auth.AuthDtos.LoginRequest;
import dev.bytebank.auth.auth.AuthDtos.RegisterRequest;
import dev.bytebank.auth.auth.AuthDtos.TokenResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SEG-02: ni contraseñas ni tokens deben aparecer nunca si alguien loguea el objeto. */
class AuthDtosTest {

    private static final String PASSWORD = "Secreta1234!";
    private static final String JWT_TOKEN = "eyJhbGciOiJIUzI1NiJ9.payload.firma-secreta";

    @Test
    void loginRequestToStringHidesThePassword() {
        String text = new LoginRequest("ana@bytebank.test", PASSWORD).toString();

        assertThat(text).doesNotContain(PASSWORD).contains("password=***").contains("ana@bytebank.test");
    }

    @Test
    void registerRequestToStringHidesThePassword() {
        String text = new RegisterRequest("ana@bytebank.test", PASSWORD, "Ana Pérez").toString();

        assertThat(text).doesNotContain(PASSWORD).contains("password=***").contains("ana@bytebank.test");
    }

    @Test
    void tokenResponseToStringHidesTheToken() {
        String text = new TokenResponse(JWT_TOKEN, "Bearer", 3600L).toString();

        assertThat(text).doesNotContain(JWT_TOKEN).contains("token=***").contains("Bearer");
    }
}
