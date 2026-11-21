package dev.bytebank.auth.auth;

import dev.bytebank.auth.auth.AuthDtos.LoginRequest;
import dev.bytebank.auth.auth.AuthDtos.RegisterRequest;
import dev.bytebank.auth.auth.AuthDtos.TokenResponse;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SEG-02: ni contraseñas ni tokens deben aparecer nunca si alguien loguea el objeto. */
class AuthDtosTest {

    private static final String PASSWORD = "Secreta1234!";
    private static final String JWT_TOKEN = "eyJhbGciOiJIUzI1NiJ9.payload.firma-secreta";
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

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

    @Test
    void registerRequestRejectsEmailLongerThan254Characters() {
        String overlongEmail = "a".repeat(60) + "@" + "b".repeat(60) + "." + "c".repeat(60) + "." + "d".repeat(60) + "." + "e".repeat(10) + ".com";
        assertThat(overlongEmail.length()).isGreaterThan(254);

        var violations = validator.validate(new RegisterRequest(overlongEmail, PASSWORD, "Ana Pérez"));

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }
}
