package org.zeveon.cashflows.statement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * JSON contract of a loan payment-schedule parser (parsers/schedules/CONTRACT.md,
 * FR-13.5). Snake_case keys; parsers verify their own row arithmetic and exit 1
 * on mismatch, so a successfully parsed schedule is internally consistent.
 *
 * @author Stanislav Vafin
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LoanScheduleDto(String source, String currency, List<Item> items) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Item(int position, LocalDate dueDate, BigDecimal principal,
                       BigDecimal interest, BigDecimal total, BigDecimal remaining) {
    }
}
