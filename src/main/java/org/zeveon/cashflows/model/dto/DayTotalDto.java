package org.zeveon.cashflows.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Converted mode: {@code expense}/{@code income} in the requested currency,
 * {@code byCurrency} is null. ORIGINAL mode: per-currency totals, no conversion.
 *
 * @author Stanislav Vafin
 */
public record DayTotalDto(LocalDate date, BigDecimal expense, BigDecimal income,
                          List<CurrencyTotalDto> byCurrency) {
}
