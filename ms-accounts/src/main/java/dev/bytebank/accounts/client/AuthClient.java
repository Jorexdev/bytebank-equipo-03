package dev.bytebank.accounts.client;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.InternalClients;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Locale;

@Component
public class AuthClient {

    private static final Logger log = LoggerFactory.getLogger(AuthClient.class);

    private final RestClient client;

    public AuthClient(InternalClients clients, @Value("${bytebank.services.auth-url}") String authUrl) {
        this.client = clients.forService(authUrl);
    }

    public record UserInfo(Long id, String email, String fullName, Role role) {

        public boolean isCliente() {
            return role == Role.CLIENTE;
        }
    }

    public UserInfo findByEmail(String email) {
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("DATOS_NO_VALIDOS", "el email es obligatorio");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        UserInfo user;
        try {
            user = InternalClients.call(() -> client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/users/by-email")
                            .queryParam("email", "{email}")
                            .build(normalizedEmail))
                    .retrieve()
                    .body(UserInfo.class));
        } catch (RemoteUnavailableException e) {
            log.error("No se pudo consultar el servicio de usuarios para resolver titular");
            throw new RemoteUnavailableException("El servicio de usuarios no está disponible temporalmente");
        }
        if (user == null || user.id() == null || user.role() == null) {
            log.error("Respuesta inválida del servicio de usuarios al resolver titular");
            throw new RemoteUnavailableException("El servicio de usuarios no está disponible temporalmente");
        }
        return user;
    }
}
