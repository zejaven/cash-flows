package org.zeveon.cashflows.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.zeveon.cashflows.entity.*;
import org.zeveon.cashflows.entity.enums.*;
import org.zeveon.cashflows.model.TransactionFilter;
import org.zeveon.cashflows.repository.*;
import org.zeveon.cashflows.repository.spec.TransactionSpecifications;
import org.zeveon.cashflows.service.impl.AggregationServiceImpl;
import org.zeveon.cashflows.service.impl.DebtExclusionSupport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Debt principal must vanish from expense/income aggregates while the interest
 * remainder stays (FR-13.3), and a fully-principal uncategorized transaction
 * must not scream "needs review". Compose postgres (cash_flows_test).
 *
 * @author Stanislav Vafin
 */
@Tag("integration")
@DataJpaTest
@Import({AggregationServiceImpl.class, DebtExclusionSupport.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5434/cash_flows_test",
        "spring.datasource.username=${DB_USERNAME:cashflows}",
        "spring.datasource.password=${DB_PASSWORD:cashflows-local}",
})
class DebtStatsExclusionIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 29);

    @Autowired
    private AggregationService aggregationService;

    @Autowired
    private DebtRepository debtRepository;

    @Autowired
    private DebtEntryRepository entryRepository;

    @Autowired
    private CounterpartyRepository counterpartyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoBean
    private FxService fxService;

    private Account account;
    private Debt debt;
    private Transaction loanPayment;
    private Transaction fullRepayment;
    private Transaction groceries;

    @BeforeEach
    void setUp() {
        entryRepository.deleteAll();
        debtRepository.deleteAll();
        counterpartyRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        when(fxService.convertAtDate(any(), any(), any(), any())).thenAnswer(invocation -> {
            BigDecimal amount = invocation.getArgument(0);
            String from = invocation.getArgument(1);
            String to = invocation.getArgument(2);
            return from.equals(to) ? Optional.of(amount) : Optional.empty();
        });

        account = accountRepository.save(Account.builder()
                .name("RSHB RUB").currency("RUB").type(AccountType.BANK)
                .startBalance(BigDecimal.ZERO).startDate(LocalDate.of(2026, 1, 1))
                .archived(false).build());
        var counterparty = counterpartyRepository.save(Counterparty.builder().name("РСХБ").build());
        debt = debtRepository.save(Debt.builder()
                .name("Ипотека").direction(DebtDirection.I_OWE).kind(DebtKind.PERSONAL)
                .counterparty(counterparty).currency("RUB")
                .openingPrincipal(new BigDecimal("100000")).anchorDate(LocalDate.of(2026, 1, 1))
                .build());

        // loan payment: 9 000 principal (excluded) + 8 023.23 interest (real expense)
        loanPayment = expense("17023.23");
        entry(loanPayment, DebtEntryRole.REPAYMENT, "9000");
        // a friend returned money: full principal, no income at all
        fullRepayment = transactionRepository.save(Transaction.builder()
                .type(TransactionType.INCOME).txnDate(DAY).account(account)
                .amount(new BigDecimal("5000")).source(TransactionSource.IMPORT).build());
        entry(fullRepayment, DebtEntryRole.ISSUE, "5000");
        // ordinary uncategorized expense — the control row
        groceries = expense("100");
    }

    private Transaction expense(String amount) {
        return transactionRepository.save(Transaction.builder()
                .type(TransactionType.EXPENSE).txnDate(DAY).account(account)
                .amount(new BigDecimal(amount)).source(TransactionSource.IMPORT).build());
    }

    private void entry(Transaction transaction, DebtEntryRole role, String principal) {
        entryRepository.save(DebtEntry.builder()
                .debt(debt).transaction(transaction).role(role)
                .txnPrincipal(new BigDecimal(principal))
                .debtPrincipal(new BigDecimal(principal))
                .build());
    }

    @Test
    @DisplayName("day totals count only the interest remainder; full-principal rows vanish")
    void dayTotalsSubtractPrincipal() {
        var totals = aggregationService.dayTotals(
                new TransactionFilter(null, null, null, null, null, null, null), "RUB");

        assertThat(totals.days()).hasSize(1);
        var day = totals.days().getFirst();
        assertThat(day.expense()).isEqualByComparingTo("8123.23");   // 8023.23 interest + 100
        assertThat(day.income()).isEqualByComparingTo("0.00");       // borrowed money is not income
    }

    @Test
    @DisplayName("summary applies the same subtraction")
    void summarySubtractsPrincipal() {
        var summary = aggregationService.summary(
                new TransactionFilter(null, null, null, null, null, null, null), DAY, "RUB");
        assertThat(summary.day().expense()).isEqualByComparingTo("8123.23");
        assertThat(summary.day().income()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("needs-review skips fully-principal movements but keeps partial ones")
    void needsReviewSpec() {
        var needing = transactionRepository.findAll(TransactionSpecifications.matching(
                new TransactionFilter(null, null, null, null, null, null, true)));

        assertThat(needing)
                .extracting(Transaction::getId)
                .containsExactlyInAnyOrder(loanPayment.getId(), groceries.getId())
                .doesNotContain(fullRepayment.getId());
    }
}
