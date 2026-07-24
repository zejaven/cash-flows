package org.zeveon.cashflows.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zeveon.cashflows.entity.Debt;
import org.zeveon.cashflows.service.impl.DebtCalculator;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stanislav Vafin
 */
class DebtCalculatorTest {

    private static Debt debt(String openingPrincipal) {
        return Debt.builder().openingPrincipal(new BigDecimal(openingPrincipal)).build();
    }

    @Test
    @DisplayName("outstanding = opening + issued - repaid")
    void outstanding() {
        assertThat(DebtCalculator.outstanding(debt("1000"),
                new BigDecimal("500"), new BigDecimal("300")))
                .isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("null sums count as zero (debt with no linked transactions yet)")
    void nullSums() {
        assertThat(DebtCalculator.outstanding(debt("2126259.84"), null, null))
                .isEqualByComparingTo("2126259.84");
        assertThat(DebtCalculator.totalPrincipal(debt("2126259.84"), null))
                .isEqualByComparingTo("2126259.84");
    }

    @Test
    @DisplayName("over-repayment goes negative rather than silently clamping")
    void overRepayment() {
        assertThat(DebtCalculator.outstanding(debt("100"), null, new BigDecimal("150")))
                .isEqualByComparingTo("-50");
    }

    @Test
    @DisplayName("totalPrincipal = opening + issued — the progress-bar denominator")
    void totalPrincipal() {
        assertThat(DebtCalculator.totalPrincipal(debt("0"), new BigDecimal("10000")))
                .isEqualByComparingTo("10000");
    }
}
