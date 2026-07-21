package org.zeveon.cashflows.model.dto;

/**
 * Day/ISO-week/calendar-month totals around the client-supplied reference date
 * (FR-2.5). Always in a concrete display currency.
 *
 * @author Stanislav Vafin
 */
public record SummaryDto(String currency, boolean incomplete,
                         PeriodTotalsDto day, PeriodTotalsDto week, PeriodTotalsDto month) {
}
