package org.zeveon.cashflows.model.dto;

import java.util.List;

/**
 * {@code incomplete} — some amounts were skipped because no rate was available
 * for their currency (fail-soft, FR-6.1).
 *
 * @author Stanislav Vafin
 */
public record DayTotalsResponse(String currency, boolean incomplete, List<DayTotalDto> days) {
}
