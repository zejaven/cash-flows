package org.zeveon.cashflows.recurring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.RecurringRule;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.AmountStrategy;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.FxService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * @author Stanislav Vafin
 */
@ExtendWith(MockitoExtension.class)
class RecurringAmountCalculatorTest {

    private static final Map<String, Double> RATES = Map.of("USD", 1.0, "THB", 32.5);

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private FxService fxService;

    @InjectMocks
    private RecurringAmountCalculator calculator;

    private final Account thbAccount = Account.builder().id(1L).name("TrueMoney THB")
            .currency("THB").build();
    private final Account usdAccount = Account.builder().id(2L).name("Alatau USD")
            .currency("USD").build();
    private final Category electricity = Category.builder().id(8L).name("electricity")
            .type(CategoryType.EXPENSE).build();

    private RecurringRule averageRule() {
        return RecurringRule.builder()
                .type(TransactionType.EXPENSE)
                .account(thbAccount)
                .category(electricity)
                .amountStrategy(AmountStrategy.AVERAGE)
                .avgLookback(3)
                .build();
    }

    private Transaction payment(Account account, String amount, LocalDate date) {
        return Transaction.builder().account(account)
                .amount(new BigDecimal(amount)).txnDate(date).build();
    }

    @Test
    @DisplayName("FIXED returns the stored amount as-is")
    void fixedAmount() {
        var rule = RecurringRule.builder()
                .amountStrategy(AmountStrategy.FIXED)
                .amount(new BigDecimal("7500"))
                .build();
        var result = calculator.currentAmount(rule);
        assertThat(result.value()).isEqualByComparingTo("7500");
        assertThat(result.approx()).isFalse();
    }

    @Test
    @DisplayName("AVERAGE = mean of last N same-currency payments, exact")
    void averageSameCurrency() {
        when(transactionRepository.findByTypeAndCategoryIdOrderByTxnDateDescIdDesc(
                eq(TransactionType.EXPENSE), eq(8L), any()))
                .thenReturn(List.of(
                        payment(thbAccount, "2502.78", LocalDate.of(2025, 8, 9)),
                        payment(thbAccount, "2176.58", LocalDate.of(2025, 7, 10)),
                        payment(thbAccount, "1302.80", LocalDate.of(2025, 6, 10))));
        var result = calculator.currentAmount(averageRule());
        // (2502.78 + 2176.58 + 1302.80) / 3 = 1994.05(3)
        assertThat(result.value()).isEqualByComparingTo("1994.05");
        assertThat(result.approx()).isFalse();
    }

    @Test
    @DisplayName("cross-currency payments are converted at their own dates and flagged approx")
    void averageCrossCurrency() {
        when(transactionRepository.findByTypeAndCategoryIdOrderByTxnDateDescIdDesc(
                eq(TransactionType.EXPENSE), eq(8L), any()))
                .thenReturn(List.of(
                        payment(thbAccount, "2000", LocalDate.of(2026, 7, 1)),
                        payment(usdAccount, "62", LocalDate.of(2026, 6, 1))));  // 62 USD -> 2015 THB
        when(fxService.ratesForDate(LocalDate.of(2026, 6, 1))).thenReturn(Optional.of(RATES));
        when(fxService.convert(new BigDecimal("62"), "USD", "THB", RATES))
                .thenReturn(Optional.of(new BigDecimal("2015")));
        var result = calculator.currentAmount(averageRule());
        assertThat(result.value()).isEqualByComparingTo("2007.50");
        assertThat(result.approx()).isTrue();
    }

    @Test
    @DisplayName("no payment history -> unknown amount")
    void averageNoHistory() {
        when(transactionRepository.findByTypeAndCategoryIdOrderByTxnDateDescIdDesc(
                eq(TransactionType.EXPENSE), eq(8L), any()))
                .thenReturn(List.of());
        var result = calculator.currentAmount(averageRule());
        assertThat(result.value()).isNull();
    }
}
