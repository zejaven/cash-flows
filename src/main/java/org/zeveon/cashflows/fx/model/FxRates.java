package org.zeveon.cashflows.fx.model;

import java.time.Instant;
import java.util.Map;

/**
 * Daily mid-market FX table, USD base. Cross rate to convert X from -> to:
 * {@code rates.get(to) / rates.get(from)}.
 *
 * @author Stanislav Vafin
 */
public record FxRates(String base, Map<String, Double> rates, Instant updatedAt) {
}
