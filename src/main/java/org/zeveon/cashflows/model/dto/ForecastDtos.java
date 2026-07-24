package org.zeveon.cashflows.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Forecast payload (FR-9.x): full daily series feed the chart; the table renders
 * only {@code eventDays} — days with discrete events (daily profile drain shows
 * as one informational item, not 365 rows).
 *
 * @author Stanislav Vafin
 */
public final class ForecastDtos {

    private ForecastDtos() {
    }

    public enum EventKind {
        RECURRING, PLANNED, PROFILE_DAILY, PROFILE_MONTHLY, TRANSACTION,
        LOAN_PAYMENT, DEBT_REPAYMENT
    }

    public record AccountRef(Long id, String name, String currency) {
    }

    /** Parallel arrays: {@code series.get(accountId).get(i)} is the balance on {@code dates.get(i)}. */
    public record Series(List<LocalDate> dates, Map<Long, List<BigDecimal>> series,
                         List<BigDecimal> total) {
    }

    public record EventItem(EventKind kind, Long refId, String label, Long accountId,
                            BigDecimal delta, Long counterAccountId, BigDecimal counterDelta,
                            boolean approx) {
    }

    public record EventDay(LocalDate date, List<EventItem> items,
                           Map<Long, BigDecimal> balances, BigDecimal total) {
    }

    public record NegativeDate(Long accountId, LocalDate date) {
    }

    public record ForecastResponse(
            String currency, boolean approxTotal,
            List<AccountRef> accounts,
            Series history, Series forecast,
            List<EventDay> eventDays,
            List<NegativeDate> negativeDates,
            NegativeDate firstNegative) {
    }
}
