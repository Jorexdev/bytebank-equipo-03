package dev.bytebank.auth.auth;

import dev.bytebank.auth.auth.AuthDtos.RegisterRequest;
import dev.bytebank.auth.user.UserRepository;
import dev.bytebank.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository users;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    JwtIssuer jwtIssuer;

    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, passwordEncoder, jwtIssuer);
    }

    @Test
    void registerConvertsConcurrentDuplicateEmailViolationToConflict() {
        when(users.existsByEmailIgnoreCase("ana@bytebank.test")).thenReturn(false);
        when(passwordEncoder.encode("Secreta1234!")).thenReturn("$2a$10$hash");
        when(users.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate", new SQLException("duplicate key", "23505")));

        assertThatThrownBy(() -> service.register(new RegisterRequest("ana@bytebank.test", "Secreta1234!", "Ana")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("EMAIL_YA_REGISTRADO");
                });
    }

    @Test
    void registerRethrowsNonUniqueIntegrityViolations() {
        when(users.existsByEmailIgnoreCase("ana@bytebank.test")).thenReturn(false);
        when(passwordEncoder.encode("Secreta1234!")).thenReturn("$2a$10$hash");
        when(users.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("value too long", new SQLException("too long", "22001")));

        assertThatThrownBy(() -> service.register(new RegisterRequest("ana@bytebank.test", "Secreta1234!", "Ana")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
