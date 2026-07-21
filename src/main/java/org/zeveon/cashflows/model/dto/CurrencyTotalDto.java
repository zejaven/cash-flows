package org.zeveon.cashflows.model.dto;

import java.math.BigDecimal;

/**
 * @author Stanislav Vafin
 */
public record CurrencyTotalDto(String currency, BigDecimal expense, BigDecimal income) {
}
