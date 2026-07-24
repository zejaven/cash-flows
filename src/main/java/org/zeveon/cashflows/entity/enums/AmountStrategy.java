package org.zeveon.cashflows.entity.enums;

/**
 * How a recurring rule's current amount is derived (FR-7.2).
 * <ul>
 *   <li>{@code FIXED} — the stored amount.</li>
 *   <li>{@code AVERAGE} — mean of the last {@code avgLookback} transactions of the
 *       category. Per <em>occurrence</em>, so a daily schedule over sparse purchases
 *       overstates the daily forecast (purchases don't happen every day).</li>
 *   <li>{@code AVERAGE_PERIOD} — total category spend over the last {@code avgLookback}
 *       <em>days</em> divided by that many days (a daily rate), then scaled to the
 *       schedule interval. Spreads sparse spending evenly across the whole period.</li>
 * </ul>
 *
 * @author Stanislav Vafin
 */
public enum AmountStrategy {
    FIXED, AVERAGE, AVERAGE_PERIOD
}
