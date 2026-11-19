package dev.bytebank.accounts.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import dev.bytebank.common.BytebankProperties;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.InternalClients;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.security.InternalKeyFilter;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthClientTest {

    private static final String INTERNAL_KEY = "clave-interna-test";

    HttpServer server;
    AuthClient authClient;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BytebankProperties props = new BytebankProperties(
                new BytebankProperties.Jwt("secreto-test-con-al-menos-32-caracteres", 3600L),
                INTERNAL_KEY,
                null
        );
        InternalClients internalClients = new InternalClients(RestClient.builder(), props, mapper);
        authClient = new AuthClient(internalClients, baseUrl);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("CAM-01: AuthClient normaliza email, escapa caracteres especiales, envía X-Internal-Key y decodifica UserInfo")
    void findByEmailSendsInternalKeyNormalizesAndEncodesQueryParam() {
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        server.createContext("/internal/users/by-email", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst(InternalKeyFilter.HEADER));
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = """
                    {"id":3,"email":"luis+1&role=admin@bytebank.test","fullName":"Luis Pérez","role":"CLIENTE"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        AuthClient.UserInfo user = authClient.findByEmail("  Luis+1&role=admin@ByteBank.test  ");

        assertThat(receivedKey.get()).isEqualTo(INTERNAL_KEY);
        assertThat(receivedQuery.get()).isEqualTo("email=luis%2B1%26role%3Dadmin%40bytebank.test");
        assertThat(user.id()).isEqualTo(3L);
        assertThat(user.email()).isEqualTo("luis+1&role=admin@bytebank.test");
        assertThat(user.fullName()).isEqualTo("Luis Pérez");
        assertThat(user.role()).isEqualTo(Role.CLIENTE);
    }

    @Test
    @DisplayName("CAM-01: AuthClient rechaza email nulo o en blanco con 400 DATOS_NO_VALIDOS")
    void findByEmailRejectsNullOrBlank() {
        assertThatThrownBy(() -> authClient.findByEmail(null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getCode()).isEqualTo("DATOS_NO_VALIDOS");
                });

        assertThatThrownBy(() -> authClient.findByEmail("   "))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getCode()).isEqualTo("DATOS_NO_VALIDOS");
                });
    }

    @Test
    @DisplayName("CAM-01: AuthClient propaga 404 USUARIO_NO_ENCONTRADO cuando ms-auth no encuentra el email")
    void findByEmailPropagatesNotFound() {
        server.createContext("/internal/users/by-email", exchange -> {
            byte[] body = """
                    {"timestamp":"2026-06-12T10:00:00Z","status":404,"error":"Not Found","code":"USUARIO_NO_ENCONTRADO","message":"No existe un usuario con ese email","path":"/internal/users/by-email"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertThatThrownBy(() -> authClient.findByEmail("nadie@bytebank.test"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("USUARIO_NO_ENCONTRADO");
                });
    }

    @Test
    @DisplayName("CAM-01: AuthClient lanza 503 sanitizado (sin URL interna ni PII) cuando ms-auth responde 500 o no responde")
    void findByEmailSanitizes503OnServerErrorAndConnectionFailure() {
        server.createContext("/internal/users/by-email", exchange -> {
            byte[] body = "Internal error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertThatThrownBy(() -> authClient.findByEmail("secreto@bytebank.test"))
                .isInstanceOf(RemoteUnavailableException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getCode()).isEqualTo("SERVICIO_NO_DISPONIBLE");
                    assertThat(apiEx.getMessage()).doesNotContain("127.0.0.1", "ms-auth", "secreto@bytebank.test");
                });

        // Fallo real de conexión (servidor detenido)
        server.stop(0);
        assertThatThrownBy(() -> authClient.findByEmail("secreto@bytebank.test"))
                .isInstanceOf(RemoteUnavailableException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getCode()).isEqualTo("SERVICIO_NO_DISPONIBLE");
                    assertThat(apiEx.getMessage()).doesNotContain("127.0.0.1", "by-email", "secreto@bytebank.test");
                });
    }

    @Test
    @DisplayName("CAM-01: AuthClient rechaza con 503 respuestas 200 vacías, sin id o sin role")
    void findByEmailRejectsIncompletePayloadsWith503() {
        server.createContext("/internal/users/by-email", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            byte[] body;
            if (query.contains("empty")) {
                body = new byte[0];
            } else if (query.contains("noid")) {
                body = "{\"email\":\"a@b.test\",\"role\":\"CLIENTE\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{\"id\":3,\"email\":\"a@b.test\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        for (String email : new String[]{"empty@bytebank.test", "noid@bytebank.test", "norole@bytebank.test"}) {
            assertThatThrownBy(() -> authClient.findByEmail(email))
                    .isInstanceOf(RemoteUnavailableException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(apiEx.getCode()).isEqualTo("SERVICIO_NO_DISPONIBLE");
                        assertThat(apiEx.getMessage()).doesNotContain("ms-auth");
                    });
        }
    }
}
