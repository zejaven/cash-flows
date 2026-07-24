package org.zeveon.cashflows.service.impl;

import org.zeveon.cashflows.entity.Debt;

import java.math.BigDecimal;

/**
 * Outstanding-balance arithmetic in the debt's own currency (FR-13.3):
 * {@code openingPrincipal + issued - repaid}. Pure functions so the math is
 * unit-testable without a database.
 *
 * @author Stanislav Vafin
 */
public final class DebtCalculator {

    private DebtCalculator() {
    }

    public static BigDecimal outstanding(Debt debt, BigDecimal issued, BigDecimal repaid) {
        return debt.getOpeningPrincipal().add(nz(issued)).subtract(nz(repaid));
    }

    /**
     * The full principal ever owed — the denominator of the repayment progress bar.
     */
    public static BigDecimal totalPrincipal(Debt debt, BigDecimal issued) {
        return debt.getOpeningPrincipal().add(nz(issued));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
