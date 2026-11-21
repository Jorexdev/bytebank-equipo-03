package dev.bytebank.common.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytebank.common.BytebankProperties;
import dev.bytebank.common.error.ApiError;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.security.InternalKeyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

/**
 * Crea clientes HTTP para llamar a otros servicios por sus rutas /internal/**.
 * Los errores 4xx del servicio remoto se traducen a {@link ApiException} con el mismo estado y código,
 * y los 5xx o fallos de conexión a {@link RemoteUnavailableException} (ver {@link #call}).
 */
public class InternalClients {

    private static final Logger log = LoggerFactory.getLogger(InternalClients.class);

    private final RestClient.Builder builder;
    private final BytebankProperties properties;
    private final ObjectMapper objectMapper;

    public InternalClients(RestClient.Builder builder, BytebankProperties properties, ObjectMapper objectMapper) {
        this.builder = builder;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RestClient forService(String baseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(InternalKeyFilter.HEADER, properties.internalKey())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw toApiException(response.getStatusCode().value(), response.getBody().readAllBytes());
                })
                .build();
    }

    /** Ejecuta una llamada remota convirtiendo los fallos de red en {@link RemoteUnavailableException}. */
    public static <T> T call(RemoteCall<T> call) {
        try {
            return call.execute();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Fallo al contactar con servicio interno", e);
            throw new RemoteUnavailableException("El servicio remoto no está disponible temporalmente");
        }
    }

    @FunctionalInterface
    public interface RemoteCall<T> {
        T execute();
    }

    private ApiException toApiException(int status, byte[] body) {
        HttpStatus httpStatus = HttpStatus.resolve(status);
        if (httpStatus == null || httpStatus.is5xxServerError()) {
            return new RemoteUnavailableException("El servicio remoto respondió con " + status);
        }
        try {
            ApiError error = objectMapper.readValue(body, ApiError.class);
            return new ApiException(httpStatus, error.code(), error.message());
        } catch (IOException e) {
            return new ApiException(httpStatus, "ERROR_REMOTO", "El servicio remoto respondió con " + status);
        }
    }
}
