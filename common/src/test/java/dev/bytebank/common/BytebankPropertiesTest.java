package dev.bytebank.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BytebankPropertiesTest {

    private static final String JWT_SECRET = "secreto-jwt-muy-sensible-con-mas-de-32-caracteres";
    private static final String INTERNAL_KEY = "clave-interna-muy-sensible";

    @Test
    void toStringHidesJwtSecretAndInternalKey() {
        var jwt = new BytebankProperties.Jwt(JWT_SECRET, 60L);
        var props = new BytebankProperties(jwt, INTERNAL_KEY, new BytebankProperties.Security(List.of("/api/auth/login")));

        assertThat(jwt.toString())
                .doesNotContain(JWT_SECRET)
                .contains("secret=***");
        assertThat(props.toString())
                .doesNotContain(JWT_SECRET)
                .doesNotContain(INTERNAL_KEY)
                .contains("internalKey=***")
                .contains("secret=***");
    }
}
