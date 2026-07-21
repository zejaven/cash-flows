package org.zeveon.cashflows.model.dto;

import java.math.BigDecimal;

/**
 * @author Stanislav Vafin
 */
public record PeriodTotalsDto(BigDecimal expense, BigDecimal income) {
}
