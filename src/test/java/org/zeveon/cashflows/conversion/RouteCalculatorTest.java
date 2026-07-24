package org.zeveon.cashflows.conversion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zeveon.cashflows.entity.ConversionStep;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reference numbers come from the user's conversion spreadsheet
 * (docs/currency-conversion-chains.md).
 *
 * @author Stanislav Vafin
 */
class RouteCalculatorTest {

    private static ConversionStep step(String from, String to, String rate, boolean inverted,
                                       String feeFixed, String feePct) {
        return ConversionStep.builder()
                .position(1)
                .fromCurrency(from)
                .toCurrency(to)
                .rate(new BigDecimal(rate))
                .rateInverted(inverted)
                .feeFixed(new BigDecimal(feeFixed))
                .feePct(new BigDecimal(feePct))
                .build();
    }

    @Test
    @DisplayName("xlsx block 8: reverse — to receive 1000 THB at 30.968 THB/USD with 7.86 THB fee ≈ 32.54 USD")
    void reverseThbForUsd() {
        // xlsx: rate =28220/911.24 = 30.9688 THB per 1 USD — TO per FROM, not inverted;
        // fee 220/28000*1000 = 7.857 THB (fee in TO currency). At pct=0 the exact
        // inverse coincides with the xlsx reverse formula.
        var steps = List.of(step("USD", "THB", "30.968790", false, "7.857143", "0"));
        var result = RouteCalculator.reverse(steps, new BigDecimal("1000"));
        assertThat(result.amountIn()).isEqualByComparingTo("32.54");
        assertThat(result.amountOut()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("xlsx block 29: forward — 375 USDT × 25732.335 VND/USDT − 3300 VND fee")
    void forwardUsdtToVnd() {
        var steps = List.of(
                step("USDT", "VND", "25732.335", false, "0", "0"),
                step("VND", "VND", "1", false, "3300", "0"));
        var result = RouteCalculator.forward(steps, new BigDecimal("375"));
        assertThat(result.amountOut()).isEqualByComparingTo("9646325.63");
    }

    @Test
    @DisplayName("forward(reverse(x)) == x even with percent fees — the xlsx reverse did not guarantee this")
    void roundTripWithPercentFee() {
        var steps = List.of(
                step("KZT", "USDT", "478.20", true, "0", "0.1"),
                step("USDT", "THB", "36.10", false, "50", "0"));
        var target = new BigDecimal("15000");
        var reverse = RouteCalculator.reverse(steps, target);
        var forward = RouteCalculator.forward(steps, reverse.amountIn());
        assertThat(forward.amountOut()).isEqualByComparingTo(target);
    }

    @Test
    @DisplayName("Berezka: to receive 60000 RUB at 6.5 KZT/RUB with 0.5% fee = 391950 KZT (matches xlsx)")
    void reverseKztForRubWithPercentFee() {
        // rate quoted as 6.5 KZT per 1 RUB -> inverted; fee 0.5% of the delivered RUB.
        // xlsx: 60000/6.5 units = 390000 KZT + 0.5% = 391950 KZT.
        var steps = List.of(step("KZT", "RUB", "6.5", true, "0", "0.5"));
        var result = RouteCalculator.reverse(steps, new BigDecimal("60000"));
        assertThat(result.amountIn()).isEqualByComparingTo("391950.00");
        assertThat(result.amountOut()).isEqualByComparingTo("60000.00");
    }

    @Test
    @DisplayName("inverted rate equals its explicit multiplier")
    void invertedRateEquivalence() {
        var inverted = List.of(step("KZT", "USDT", "478.20", true, "0", "0"));
        var multiplier = List.of(step("KZT", "USDT", "0.002091", false, "0", "0"));
        var a = RouteCalculator.forward(inverted, new BigDecimal("200000"));
        var b = RouteCalculator.forward(multiplier, new BigDecimal("200000"));
        // 200000/478.20 = 418.23 exactly; the 6-digit multiplier is close but coarser
        assertThat(a.amountOut()).isEqualByComparingTo("418.24");
        assertThat(a.amountOut().subtract(b.amountOut()).abs())
                .isLessThan(new BigDecimal("0.1"));
    }

    @Test
    @DisplayName("fixed fee is subtracted from gross, then the percent is charged on the delivered amount")
    void feeOrder() {
        var steps = List.of(step("USD", "NZD", "2", false, "1", "10"));
        // 100 USD -> gross 200 NZD -> −1 fixed = 199 -> /(1 + 10%) = 180.909…
        var result = RouteCalculator.forward(steps, new BigDecimal("100"));
        assertThat(result.amountOut()).isEqualByComparingTo("180.91");
        assertThat(result.effectiveRate()).isEqualByComparingTo("0.552764");
    }

    @Test
    @DisplayName("amount too small to cover fees -> error")
    void tooSmallForFees() {
        var steps = List.of(step("USD", "NZD", "2", false, "50", "0"));
        assertThatThrownBy(() -> RouteCalculator.forward(steps, new BigDecimal("10")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");
    }
}
