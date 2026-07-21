package org.zeveon.cashflows.conversion;

import org.zeveon.cashflows.entity.ConversionStep;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure conversion-chain math (FR-5.3). Per step with multiplier
 * {@code m = rateInverted ? 1/rate : rate} (TO per 1 FROM, DECIMAL64):
 * <pre>
 *   forward: out = in · m · (1 − pct/100) − feeFixed
 *   reverse: in  = (out + feeFixed) / (m · (1 − pct/100))
 * </pre>
 * The reverse is the exact algebraic inverse of the forward — unlike the source
 * spreadsheet (docs/currency-conversion-chains.md), whose reverse formula applies
 * the percent fee to the source side and only matches at pct = 0. The exact
 * inverse guarantees {@code forward(reverse(x)) == x}, which is what the
 * spreadsheet's mirrored blocks were trying to verify.
 *
 * @author Stanislav Vafin
 */
public final class RouteCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private RouteCalculator() {
    }

    public record StepResult(int position, String fromCurrency, String toCurrency,
                             BigDecimal amountIn, BigDecimal amountOut) {
    }

    /**
     * @param effectiveRate FROM units per 1 TO across the whole chain
     *                      ("13.29 KZT/THB"), null when amountOut is 0
     */
    public record Result(List<StepResult> steps, BigDecimal amountIn, BigDecimal amountOut,
                         BigDecimal effectiveRate) {
    }

    /** "How much comes out of {@code amountIn}." */
    public static Result forward(List<ConversionStep> steps, BigDecimal amountIn) {
        var results = new ArrayList<StepResult>();
        var current = amountIn;
        for (var step : steps) {
            var out = applyForward(step, current);
            if (out.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Amount is too small to cover the fees of step %d (%s → %s)"
                                .formatted(step.getPosition(), step.getFromCurrency(),
                                        step.getToCurrency()));
            }
            results.add(new StepResult(step.getPosition(), step.getFromCurrency(),
                    step.getToCurrency(), scale(current), scale(out)));
            current = out;
        }
        return build(results, amountIn, current);
    }

    /** "How much must go in to receive {@code amountOut}." */
    public static Result reverse(List<ConversionStep> steps, BigDecimal amountOut) {
        var inputs = new BigDecimal[steps.size() + 1];
        inputs[steps.size()] = amountOut;
        for (var index = steps.size() - 1; index >= 0; index--) {
            inputs[index] = applyReverse(steps.get(index), inputs[index + 1]);
        }
        var results = new ArrayList<StepResult>();
        for (var index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            results.add(new StepResult(step.getPosition(), step.getFromCurrency(),
                    step.getToCurrency(), scale(inputs[index]), scale(inputs[index + 1])));
        }
        return build(results, inputs[0], amountOut);
    }

    private static BigDecimal applyForward(ConversionStep step, BigDecimal in) {
        return in.multiply(multiplier(step), MC)
                .multiply(pctKeep(step), MC)
                .subtract(step.getFeeFixed());
    }

    private static BigDecimal applyReverse(ConversionStep step, BigDecimal out) {
        return out.add(step.getFeeFixed())
                .divide(multiplier(step).multiply(pctKeep(step), MC), MC);
    }

    /** TO units per 1 FROM. */
    private static BigDecimal multiplier(ConversionStep step) {
        return step.isRateInverted()
                ? BigDecimal.ONE.divide(step.getRate(), MC)
                : step.getRate();
    }

    private static BigDecimal pctKeep(ConversionStep step) {
        return BigDecimal.ONE.subtract(step.getFeePct().divide(HUNDRED, MC));
    }

    private static Result build(List<StepResult> steps, BigDecimal amountIn, BigDecimal amountOut) {
        var effectiveRate = amountOut.signum() == 0 ? null
                : amountIn.divide(amountOut, MC).setScale(6, RoundingMode.HALF_UP);
        return new Result(steps, scale(amountIn), scale(amountOut), effectiveRate);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
