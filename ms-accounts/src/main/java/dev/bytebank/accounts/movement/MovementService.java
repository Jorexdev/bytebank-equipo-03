package dev.bytebank.accounts.movement;

import dev.bytebank.accounts.account.Account;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.web.PageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class MovementService {

    private static final Instant MIN = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("3000-01-01T00:00:00Z");
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_ZONE = "Europe/Madrid";

    private final MovementRepository movements;
    private final ZoneId zone;

    @Autowired
    public MovementService(MovementRepository movements,
                           @Value("${bytebank.zone:" + DEFAULT_ZONE + "}") String zone) {
        this.movements = movements;
        this.zone = ZoneId.of(zone == null || zone.isBlank() ? DEFAULT_ZONE : zone);
    }

    MovementService(MovementRepository movements) {
        this(movements, DEFAULT_ZONE);
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementResponse> statement(Account account, LocalDate from, LocalDate to, int page, int size) {
        validateRequest(from, to, page, size);

        Instant start = from == null ? MIN : from.atStartOfDay(zone).toInstant();
        Instant end = to == null ? MAX : toInclusiveEnd(to);

        long total = movements.countInRange(account.getId(), start, end);
        int totalPages = total == 0 ? 0 : (int) ((total - 1) / size + 1);
        long rawOffset = (long) page * size;
        if (total == 0 || rawOffset >= total) {
            return new PageResponse<>(List.of(), page, size, total, totalPages);
        }

        int offset = Math.toIntExact(rawOffset);
        List<MovementResponse> content = movements.findSlice(account.getId(), start, end, size, offset)
                .stream().map(MovementResponse::from).toList();
        return new PageResponse<>(content, page, size, total, totalPages);
    }

    private Instant toInclusiveEnd(LocalDate to) {
        return to.equals(LocalDate.MAX) ? MAX : to.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static void validateRequest(LocalDate from, LocalDate to, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("DATOS_NO_VALIDOS",
                    "Paginación no válida: page debe ser >= 0 y size entre 1 y " + MAX_PAGE_SIZE);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("RANGO_FECHAS_INVALIDO",
                    "La fecha 'from' no puede ser posterior a 'to'");
        }
    }
}
