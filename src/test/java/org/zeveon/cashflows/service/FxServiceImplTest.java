package org.zeveon.cashflows.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zeveon.cashflows.entity.FxRateSnapshot;
import org.zeveon.cashflows.fx.FxFacade;
import org.zeveon.cashflows.fx.model.FxRates;
import org.zeveon.cashflows.repository.FxRateSnapshotRepository;
import org.zeveon.cashflows.service.impl.FxServiceImpl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * @author Stanislav Vafin
 */
@ExtendWith(MockitoExtension.class)
class FxServiceImplTest {

    private static final Map<String, Double> RATES = Map.of("USD", 1.0, "NZD", 1.71, "KZT", 485.4);

    @Mock
    private FxFacade fxFacade;

    @Mock
    private FxRateSnapshotRepository snapshotRepository;

    @InjectMocks
    private FxServiceImpl fxService;

    @Test
    @DisplayName("cross rate = rates[to]/rates[from]")
    void convertCrossRate() {
        var result = fxService.convert(new BigDecimal("100"), "USD", "NZD", RATES);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("171");
    }

    @Test
    @DisplayName("USDT is pegged to USD")
    void convertUsdtPeg() {
        var result = fxService.convert(new BigDecimal("10"), "USDT", "NZD", RATES);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("17.1");
    }

    @Test
    @DisplayName("unknown currency -> empty (fail-soft)")
    void convertUnknownCurrency() {
        assertThat(fxService.convert(BigDecimal.ONE, "XXX", "NZD", RATES)).isEmpty();
    }

    @Test
    @DisplayName("ratesForDate prefers the latest snapshot on or before the date")
    void ratesForDateUsesSnapshot() {
        var date = LocalDate.of(2026, 7, 21);
        when(snapshotRepository.findTopBySnapshotDateLessThanEqualOrderBySnapshotDateDesc(date))
                .thenReturn(Optional.of(FxRateSnapshot.builder()
                        .snapshotDate(date.minusDays(2))
                        .rates(RATES)
                        .build()));
        assertThat(fxService.ratesForDate(date)).contains(RATES);
    }

    @Test
    @DisplayName("ratesForDate falls back to the current cache before the first snapshot")
    void ratesForDateFallsBackToCache() {
        when(snapshotRepository.findTopBySnapshotDateLessThanEqualOrderBySnapshotDateDesc(any()))
                .thenReturn(Optional.empty());
        when(fxFacade.getRates())
                .thenReturn(Optional.of(new FxRates("USD", RATES, Instant.now())));
        assertThat(fxService.ratesForDate(LocalDate.of(2026, 1, 1))).contains(RATES);
    }
}
