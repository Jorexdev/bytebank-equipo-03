package dev.bytebank.notifications.notification;

import dev.bytebank.notifications.event.MovementEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private static final String IBAN = "ES7639990001000000000011";

    private final NotificationRepository repositorio = mock(NotificationRepository.class);
    private final NotificationService servicio = new NotificationService(repositorio);

    @Test
    void conservaImportesMonetariosExactosAlCrearNotificacion() {
        BigDecimal importe = new BigDecimal("123456789012345.67");
        BigDecimal saldo = new BigDecimal("234567890123456.78");
        MovementEvent evento = new MovementEvent(
                1L, IBAN, 2L, "INGRESO",
                importe, saldo, null, "REF-1", Instant.now()
        );

        servicio.notifyOwner(evento);

        Notification aviso = this.avisoGuardado();
        assertThat(aviso.getAmount()).isEqualTo(importe);
        assertThat(aviso.getBalanceAfter()).isEqualTo(saldo);
    }

    @Test
    void copiaTodosLosDatosDelEventoEnElAviso() {
        Instant creado = Instant.parse("2026-11-17T12:00:00Z");
        MovementEvent evento = new MovementEvent(
                31L, IBAN, 42L, "TRANSFERENCIA_RECIBIDA",
                new BigDecimal("25.50"), new BigDecimal("1025.50"),
                "Nómina", "REF-31", creado
        );

        servicio.notifyOwner(evento);

        Notification aviso = this.avisoGuardado();
        assertThat(aviso.getUserId()).isEqualTo(42L);
        assertThat(aviso.getMovementId()).isEqualTo(31L);
        assertThat(aviso.getIban()).isEqualTo(IBAN);
        assertThat(aviso.getType()).isEqualTo("TRANSFERENCIA_RECIBIDA");
        assertThat(aviso.getAmount()).isEqualByComparingTo("25.50");
        assertThat(aviso.getBalanceAfter()).isEqualByComparingTo("1025.50");
        assertThat(aviso.getReference()).isEqualTo("REF-31");
        assertThat(aviso.getCreatedAt()).isEqualTo(creado);
        assertThat(aviso.getMessage()).isEqualTo(NotificationMessages.of(evento));
    }

    @Test
    void noGuardaUnMovimientoQueYaTieneAviso() {
        when(repositorio.existsByMovementId(7L)).thenReturn(true);

        servicio.notifyOwner(this.evento(7L));

        verify(repositorio, never()).saveAndFlush(any());
        verify(repositorio, never()).save(any());
    }

    @Test
    void guardaCadaMovimientoDistintoAunqueLleguenSeguidos() {
        servicio.notifyOwner(this.evento(10L));
        servicio.notifyOwner(this.evento(11L));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Notification::getMovementId)
                .containsExactly(10L, 11L);
    }

    @Test
    void ignoraElConflictoCuandoOtroProcesamientoGanoLaCarrera() {
        when(repositorio.existsByMovementId(8L)).thenReturn(false);
        when(repositorio.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("movimiento duplicado"));

        assertThatCode(() -> servicio.notifyOwner(this.evento(8L)))
                .doesNotThrowAnyException();

        verify(repositorio, times(1)).saveAndFlush(any());
    }

    @Test
    void guardaConFlushParaEvaluarLaConstraintDentroDelIntento() {
        servicio.notifyOwner(this.evento(12L));

        verify(repositorio).saveAndFlush(any());
        verify(repositorio, never()).save(any());
    }

    @Test
    void listaAvisosConLaPaginaYElOrdenSolicitados() {
        when(repositorio.findByUserId(eq(5L), any())).thenReturn(Page.empty());

        servicio.listForUser(5L, 2, 25);

        ArgumentCaptor<Pageable> paginacion = ArgumentCaptor.forClass(Pageable.class);
        verify(repositorio).findByUserId(eq(5L), paginacion.capture());
        assertThat(paginacion.getValue().getPageNumber()).isEqualTo(2);
        assertThat(paginacion.getValue().getPageSize()).isEqualTo(25);
        assertThat(paginacion.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(paginacion.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    private Notification avisoGuardado() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private MovementEvent evento(Long movimientoId) {
        return new MovementEvent(
                movimientoId, IBAN, 2L, "INGRESO",
                new BigDecimal("100.00"), new BigDecimal("1100.00"), null, "REF-1", Instant.now()
        );
    }
}
