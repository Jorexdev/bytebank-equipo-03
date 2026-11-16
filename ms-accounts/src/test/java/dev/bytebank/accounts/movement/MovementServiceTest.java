package dev.bytebank.accounts.movement;

import dev.bytebank.accounts.account.Account;
import dev.bytebank.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovementServiceTest {

    @Mock
    MovementRepository movements;

    @InjectMocks
    MovementService service;

    @Test
    void returnsTheRequestedPage() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(1L);
        when(movements.countInRange(anyLong(), any(), any())).thenReturn(3L);
        when(movements.findSlice(anyLong(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                mock(Movement.class), mock(Movement.class), mock(Movement.class)));

        var page = service.statement(account, null, null, 0, 10);

        assertThat(page.content()).hasSize(3);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("BUG-03: la primera página empieza en OFFSET 0 para no omitir el movimiento más reciente")
    void firstPageStartsAtZeroOffset() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(1L);
        when(movements.countInRange(eq(1L), any(), any())).thenReturn(5L);
        when(movements.findSlice(eq(1L), any(), any(), eq(20), anyInt())).thenReturn(List.of());

        service.statement(account, null, null, 0, 20);

        verify(movements).findSlice(eq(1L), any(), any(), eq(20), eq(0));
    }

    @Test
    @DisplayName("BUG-03: las páginas siguientes usan OFFSET base 0 (page * size) sin saltarse movimientos")
    void subsequentPagesUseZeroBasedOffset() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(1L);
        when(movements.countInRange(eq(1L), any(), any())).thenReturn(45L);
        when(movements.findSlice(eq(1L), any(), any(), eq(20), anyInt())).thenReturn(List.of());

        service.statement(account, null, null, 1, 20);
        service.statement(account, null, null, 2, 20);

        verify(movements).findSlice(eq(1L), any(), any(), eq(20), eq(20));
        verify(movements).findSlice(eq(1L), any(), any(), eq(20), eq(40));
    }

    @Test
    @DisplayName("BUG-03: el filtro por fechas incluye todo el último día (to) y respeta Europe/Madrid (verano UTC+2)")
    void filterByDateIncludesLastDay() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(1L);
        when(movements.countInRange(eq(1L), any(), any())).thenReturn(0L);

        service.statement(account, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 0, 10);

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(movements).countInRange(eq(1L), startCaptor.capture(), endCaptor.capture());

        // Mayo en Europe/Madrid es CEST (UTC+2):
        // 2026-05-01 00:00 Madrid = 2026-04-30T22:00:00Z
        // fin exclusivo = 2026-06-01 00:00 Madrid = 2026-05-31T22:00:00Z (incluye todo el 31 de mayo)
        assertThat(startCaptor.getValue()).isEqualTo(Instant.parse("2026-04-30T22:00:00Z"));
        assertThat(endCaptor.getValue()).isEqualTo(Instant.parse("2026-05-31T22:00:00Z"));
    }

    @Test
    @DisplayName("BUG-03: el filtro por fechas respeta Europe/Madrid en horario de invierno (UTC+1)")
    void filterByDateRespectsMadridWinterTimezone() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(1L);
        when(movements.countInRange(eq(1L), any(), any())).thenReturn(0L);

        service.statement(account, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15), 0, 10);

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(movements).countInRange(eq(1L), startCaptor.capture(), endCaptor.capture());

        assertThat(startCaptor.getValue()).isEqualTo(Instant.parse("2026-01-14T23:00:00Z"));
        assertThat(endCaptor.getValue()).isEqualTo(Instant.parse("2026-01-15T23:00:00Z"));
    }

    @Test
    @DisplayName("BUG-03: rechaza rango de fechas inválido (from posterior a to) con 400")
    void rejectsFromAfterTo() {
        Account account = mock(Account.class);

        assertThatThrownBy(() -> service.statement(
                account, LocalDate.of(2026, 5, 31), LocalDate.of(2026, 5, 1), 0, 20))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("BUG-03: rechaza page negativo o size fuera de [1, 100] con 400")
    void rejectsInvalidPagination() {
        Account account = mock(Account.class);

        assertThatThrownBy(() -> service.statement(account, null, null, -1, 20))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.statement(account, null, null, 0, 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.statement(account, null, null, 0, 101))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("BUG-03: página fuera de rango devuelve contenido vacío manteniendo totales exactos")
    void returnsEmptyPageWhenOffsetExceedsTotal() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(1L);
        when(movements.countInRange(eq(1L), any(), any())).thenReturn(45L);

        var page = service.statement(account, null, null, 3, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(45);
        assertThat(page.totalPages()).isEqualTo(3);
        verify(movements, never()).findSlice(anyLong(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("BUG-03: respeta la zona horaria inyectada por configuración (bytebank.zone)")
    void usesConfiguredZoneWhenProvided() {
        MovementService utcService = new MovementService(movements, "UTC");
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(1L);
        when(movements.countInRange(eq(1L), any(), any())).thenReturn(0L);

        utcService.statement(account, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 0, 10);

        verify(movements).countInRange(1L,
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"));
    }
}
