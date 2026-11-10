package dev.bytebank.auth.auth;

import dev.bytebank.auth.auth.AuthDtos.LoginRequest;
import dev.bytebank.auth.auth.AuthDtos.RegisterRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SEG-02: la contraseña no debe aparecer nunca si alguien loguea el objeto. */
class AuthDtosTest {

    private static final String PASSWORD = "Secreta1234!";

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
}
