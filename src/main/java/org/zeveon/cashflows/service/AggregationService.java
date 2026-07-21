package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.TransactionFilter;
import org.zeveon.cashflows.model.dto.DayTotalsResponse;
import org.zeveon.cashflows.model.dto.SummaryDto;

import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
public interface AggregationService {

    /**
     * Per-day expense/income totals over the filtered range (FR-2.5). Transfers
     * are excluded. {@code currency == "ORIGINAL"} returns per-currency totals
     * without conversion; otherwise amounts are converted with the rate table of
     * each transaction's own date (FR-6.3).
     */
    DayTotalsResponse dayTotals(TransactionFilter filter, String currency);

    /**
     * Day/ISO-week/calendar-month totals around the client-supplied reference
     * date — "today" is always the client's date, never the server's (timezone rule).
     */
    SummaryDto summary(TransactionFilter filter, LocalDate referenceDate, String currency);
}
