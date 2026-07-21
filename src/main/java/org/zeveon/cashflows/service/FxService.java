package org.zeveon.cashflows.service;

import org.zeveon.cashflows.fx.model.FxRates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * @author Stanislav Vafin
 */
public interface FxService {

    /**
     * Current rate table from the daily cache; empty when the source is unavailable.
     */
    Optional<FxRates> currentRates();

    /**
     * Rate table effective on the given date: the latest stored snapshot taken on
     * or before it, falling back to the current cache for dates before the first
     * snapshot (FR-6.3).
     */
    Optional<Map<String, Double>> ratesForDate(LocalDate date);

    /**
     * Cross-rate conversion {@code rates[to]/rates[from]}; USDT is pegged to USD.
     * Empty when either currency is missing from the table (fail-soft, FR-6.1).
     */
    Optional<BigDecimal> convert(BigDecimal amount, String from, String to, Map<String, Double> rates);

    /**
     * Converts with the rate table effective on the given date (FR-6.3) — the
     * shared "own-date conversion" used by aggregates, averages and statistics.
     */
    Optional<BigDecimal> convertAtDate(BigDecimal amount, String from, String to, LocalDate date);

    /**
     * Persists today's snapshot if the source is reachable and none exists yet.
     */
    void snapshotToday();
}
