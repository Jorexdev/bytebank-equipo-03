package dev.bytebank.fraud;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Umbrales de las reglas antifraude.
 *
 * @param blockThreshold          límite por operación
 * @param externalReviewThreshold importe a partir del cual una transferencia a otra entidad va a revisión
 * @param frequencyLimit          número de transferencias permitidas por cuenta dentro de la ventana
 * @param frequencyWindow         ventana temporal en la que se cuentan las transferencias
 */
@Validated
@ConfigurationProperties(prefix = "bytebank.fraud")
public record FraudProperties(@NotNull @Positive BigDecimal blockThreshold,
                              @NotNull @Positive BigDecimal externalReviewThreshold,
                              @Positive int frequencyLimit,
                              @NotNull Duration frequencyWindow) {
}
