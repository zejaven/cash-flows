package org.zeveon.cashflows.model.dto;

import java.time.Instant;
import java.util.Map;

/**
 * @author Stanislav Vafin
 */
public record FxRatesDto(String base, Map<String, Double> rates, Instant updatedAt) {
}
