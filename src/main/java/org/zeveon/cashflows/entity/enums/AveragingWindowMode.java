package org.zeveon.cashflows.entity.enums;

/**
 * How an averaging profile's history window is bounded (FR-8.x):
 * <ul>
 *   <li>{@code FIXED} — an explicit calendar range {@code [periodFrom, periodTo]};
 *       stable over time, so you can compare "how I spent in that period" against
 *       another profile.</li>
 *   <li>{@code ROLLING} — the last {@code rollingDays} days ending at today; always
 *       reflects recent spending without re-editing the dates.</li>
 * </ul>
 *
 * @author Stanislav Vafin
 */
public enum AveragingWindowMode {
    FIXED, ROLLING
}
