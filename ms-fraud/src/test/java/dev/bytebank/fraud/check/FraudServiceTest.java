package dev.bytebank.fraud.check;

import dev.bytebank.common.iban.Iban;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudServiceTest {

    @Mock
    FraudCheckRepository checks;

    FraudService service;

    @BeforeEach
    void setUp() {
        service = new FraudService(List.of(), checks, Clock.fixed(Instant.parse("2026-06-12T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void listTreatsNullEmptyAndBlankIbanAsUnfiltered() {
        when(checks.findAll(any(Pageable.class))).thenReturn(Page.empty());

        service.list(null, 0, 20);
        service.list("", 0, 20);
        service.list("   ", 0, 20);

        verify(checks, times(3)).findAll(any(Pageable.class));
        verify(checks, never()).findByOriginIban(any(), any());
    }

    @Test
    void listNormalizesProvidedIban() {
        String iban = Iban.generate(1);
        when(checks.findByOriginIban(eq(iban), any(Pageable.class))).thenReturn(Page.empty());

        service.list(" " + iban.toLowerCase() + " ", 0, 20);

        verify(checks).findByOriginIban(eq(iban), any(Pageable.class));
    }
}
