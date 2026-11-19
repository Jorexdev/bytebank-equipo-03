package dev.bytebank.notifications.notification;

import dev.bytebank.notifications.event.MovementEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
                1L, IBAN, 2L, null, "INGRESO",
                importe, saldo, null, "REF-1", Instant.now()
        );

        servicio.notifyHolders(evento);

        Notification aviso = this.avisoGuardado();
        assertThat(aviso.getAmount()).isEqualTo(importe);
        assertThat(aviso.getBalanceAfter()).isEqualTo(saldo);
    }

    @Test
    void copiaTodosLosDatosDelEventoEnElAviso() {
        Instant creado = Instant.parse("2026-11-17T12:00:00Z");
        MovementEvent evento = new MovementEvent(
                31L, IBAN, 42L, null, "TRANSFERENCIA_RECIBIDA",
                new BigDecimal("25.50"), new BigDecimal("1025.50"),
                "Nómina", "REF-31", creado
        );

        servicio.notifyHolders(evento);

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
        when(repositorio.existsByMovementIdAndUserId(7L, 2L)).thenReturn(true);

        servicio.notifyHolders(this.evento(7L));

        verify(repositorio).existsByMovementIdAndUserId(7L, 2L);
        verify(repositorio, never()).saveAndFlush(any());
        verify(repositorio, never()).save(any());
    }

    @Test
    void guardaElMismoMovimientoParaUsuariosDistintos() {
        MovementEvent eventoUsuarioDos = this.evento(13L);
        MovementEvent eventoUsuarioTres = new MovementEvent(
                13L, IBAN, 3L, null, "INGRESO",
                new BigDecimal("100.00"), new BigDecimal("1100.00"), null, "REF-1", Instant.now()
        );

        servicio.notifyHolders(eventoUsuarioDos);
        servicio.notifyHolders(eventoUsuarioTres);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Notification::getMovementId)
                .containsExactly(13L, 13L);
        assertThat(captor.getAllValues())
                .extracting(Notification::getUserId)
                .containsExactly(2L, 3L);
        verify(repositorio).existsByMovementIdAndUserId(13L, 2L);
        verify(repositorio).existsByMovementIdAndUserId(13L, 3L);
    }

    @Test
    void guardaCadaMovimientoDistintoAunqueLleguenSeguidos() {
        servicio.notifyHolders(this.evento(10L));
        servicio.notifyHolders(this.evento(11L));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Notification::getMovementId)
                .containsExactly(10L, 11L);
    }

    @Test
    void ignoraElConflictoCuandoOtroProcesamientoGanoLaCarrera() {
        when(repositorio.existsByMovementIdAndUserId(8L, 2L)).thenReturn(false);
        when(repositorio.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("movimiento duplicado"));

        assertThatCode(() -> servicio.notifyHolders(this.evento(8L)))
                .doesNotThrowAnyException();

        verify(repositorio, times(1)).saveAndFlush(any());
    }

    @Test
    void guardaConFlushParaEvaluarLaConstraintDentroDelIntento() {
        servicio.notifyHolders(this.evento(12L));

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

    @Test
    void creaUnAvisoPorCadaTitularDelEvento() {
        MovementEvent evento = new MovementEvent(
                20L, IBAN, 2L, List.of(2L, 3L, 4L), "INGRESO",
                new BigDecimal("100.00"), new BigDecimal("1100.00"), null, "REF-20", Instant.now()
        );

        servicio.notifyHolders(evento);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio, times(3)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Notification::getUserId)
                .containsExactly(2L, 3L, 4L);
        verify(repositorio).existsByMovementIdAndUserId(20L, 2L);
        verify(repositorio).existsByMovementIdAndUserId(20L, 3L);
        verify(repositorio).existsByMovementIdAndUserId(20L, 4L);
    }

    @Test
    void eventoSinHolderIdsNotificaSoloAlOwner() {
        MovementEvent evento = new MovementEvent(
                21L, IBAN, 2L, null, "INGRESO",
                new BigDecimal("100.00"), new BigDecimal("1100.00"), null, "REF-21", Instant.now()
        );

        servicio.notifyHolders(evento);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(2L);
        verify(repositorio).existsByMovementIdAndUserId(21L, 2L);
        verify(repositorio, never()).existsByMovementIdAndUserId(21L, 3L);
    }

    @Test
    void noDuplicaLosAvisosDeLosTitulares() {
        MovementEvent evento = new MovementEvent(
                22L, IBAN, 2L, List.of(2L, 3L, 3L, 4L), "INGRESO",
                new BigDecimal("100.00"), new BigDecimal("1100.00"), null, "REF-22", Instant.now()
        );

        servicio.notifyHolders(evento);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio, times(3)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Notification::getUserId)
                .containsExactly(2L, 3L, 4L);
    }

    @Test
    void noDuplicaAvisosSiKafkaReenviaElEventoParaVariosTitulares() {
        when(repositorio.existsByMovementIdAndUserId(23L, 2L)).thenReturn(true);
        when(repositorio.existsByMovementIdAndUserId(23L, 3L)).thenReturn(true);
        MovementEvent evento = new MovementEvent(
                23L, IBAN, 2L, List.of(2L, 3L), "INGRESO",
                new BigDecimal("100.00"), new BigDecimal("1100.00"), null, "REF-23", Instant.now()
        );

        servicio.notifyHolders(evento);

        verify(repositorio).existsByMovementIdAndUserId(23L, 2L);
        verify(repositorio).existsByMovementIdAndUserId(23L, 3L);
        verify(repositorio, never()).saveAndFlush(any());
    }

    @Test
    void sigueNotificandoAlRestoSiUnTitularYaTieneAviso() {
        when(repositorio.existsByMovementIdAndUserId(24L, 2L)).thenReturn(true);
        MovementEvent evento = new MovementEvent(
                24L, IBAN, 2L, List.of(2L, 3L), "INGRESO",
                new BigDecimal("100.00"), new BigDecimal("1100.00"), null, "REF-24", Instant.now()
        );

        servicio.notifyHolders(evento);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(3L);
    }

    @Test
    void ignoraElConflictoDeUnTitularYSigueConLosDemas() {
        when(repositorio.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("movimiento duplicado"))
                .thenAnswer(inv -> inv.getArgument(0));
        MovementEvent evento = new MovementEvent(
                25L, IBAN, 2L, List.of(2L, 3L), "INGRESO",
                new BigDecimal("100.00"), new BigDecimal("1100.00"), null, "REF-25", Instant.now()
        );

        assertThatCode(() -> servicio.notifyHolders(evento)).doesNotThrowAnyException();

        verify(repositorio, times(2)).saveAndFlush(any());
    }

    private Notification avisoGuardado() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repositorio).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private MovementEvent evento(Long movimientoId) {
        return new MovementEvent(
                movimientoId, IBAN, 2L, null, "INGRESO",
                new BigDecimal("100.00"), new BigDecimal("1100.00"), null, "REF-1", Instant.now()
        );
    }
}
