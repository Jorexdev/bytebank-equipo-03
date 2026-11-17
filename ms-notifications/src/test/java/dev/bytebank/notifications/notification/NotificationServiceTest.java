package dev.bytebank.notifications.notification;

import dev.bytebank.notifications.event.MovementEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationServiceTest {

    @Test
    void conservaImportesMonetariosExactosAlCrearNotificacion() {
        NotificationRepository repositorio = mock(NotificationRepository.class);
        NotificationService servicio = new NotificationService(repositorio);
        BigDecimal importe = new BigDecimal("123456789012345.67");
        BigDecimal saldo = new BigDecimal("234567890123456.78");
        MovementEvent evento = new MovementEvent(
                1L, "ES7639990001000000000011", 2L, "INGRESO",
                importe, saldo, null, "REF-1", Instant.now()
        );

        servicio.notifyOwner(evento);

        ArgumentCaptor<Notification> notificacionCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio).save(notificacionCaptor.capture());
        assertThat(notificacionCaptor.getValue().getAmount()).isEqualTo(importe);
        assertThat(notificacionCaptor.getValue().getBalanceAfter()).isEqualTo(saldo);
    }
}
