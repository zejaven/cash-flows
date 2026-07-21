package org.zeveon.cashflows.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
public final class StatsDtos {

    private StatsDtos() {
    }

    public enum Granularity {MONTH, WEEK}

    /** {@code categoryId == null} — the "uncategorized" pseudo-category (needs review). */
    public record CategoryAmount(Long categoryId, String name, String color, BigDecimal amount) {
    }

    public record Bucket(LocalDate periodStart, LocalDate periodEnd, String label,
                         BigDecimal total, List<CategoryAmount> byCategory) {
    }

    public record CategoriesResponse(String currency, boolean incomplete, List<Bucket> buckets) {
    }

    /** FR-10.4; both values positive, diff = forecast − actual, diffPct null when forecast = 0. */
    public record FactVsForecast(String currency, BigDecimal forecast, BigDecimal actual,
                                 BigDecimal diff, BigDecimal diffPct, boolean approx) {
    }
}
