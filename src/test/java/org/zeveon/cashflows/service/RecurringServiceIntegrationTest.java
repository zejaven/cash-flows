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
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.enums.*;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.model.dto.RecurringDtos.*;
import org.zeveon.cashflows.recurring.RecurringAmountCalculator;
import org.zeveon.cashflows.repository.*;
import org.zeveon.cashflows.service.impl.RecurringServiceImpl;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against the compose postgres (cash_flows_test) — tagged integration.
 *
 * @author Stanislav Vafin
 */
@Tag("integration")
@DataJpaTest
@Import({RecurringServiceImpl.class, RecurringAmountCalculator.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5434/cash_flows_test",
        "spring.datasource.username=${DB_USERNAME:cashflows}",
        "spring.datasource.password=${DB_PASSWORD:cashflows-local}",
})
class RecurringServiceIntegrationTest {

    @Autowired
    private RecurringService recurringService;

    @Autowired
    private RecurringRuleRepository ruleRepository;

    @Autowired
    private RecurringOccurrenceRepository occurrenceRepository;

    @Autowired
    private PlannedTransactionRepository plannedRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoBean
    private FxService fxService;

    private Account account;
    private Category rent;
    private final LocalDate today = LocalDate.of(2026, 7, 21);

    @BeforeEach
    void setUp() {
        occurrenceRepository.deleteAll();
        ruleRepository.deleteAll();
        plannedRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        account = accountRepository.save(Account.builder()
                .name("TrueMoney THB").currency("THB").type(AccountType.CASH)
                .startBalance(BigDecimal.ZERO).startDate(LocalDate.of(2026, 1, 1))
                .archived(false).build());
        rent = categoryRepository.findByNameAndType("rent", CategoryType.EXPENSE).orElseThrow();
    }

    private RecurringRuleDto monthlyRentRule() {
        return recurringService.create(new RecurringRuleRequest(
                "Rent", TransactionType.EXPENSE, account.getId(), null, rent.getId(),
                ScheduleFrequency.MONTHLY, 2, null, null,
                LocalDate.of(2026, 6, 1), null,
                AmountStrategy.FIXED, new BigDecimal("7500"), null, null, null, null), today);
    }

    @Test
    @DisplayName("queue lists pending occurrences; confirm creates a transaction and removes it; double confirm -> 409")
    void confirmFlow() {
        var rule = monthlyRentRule();

        var queue = recurringService.queue(today);
        // June 2 and July 2 are due
        assertThat(queue).hasSize(2);
        assertThat(queue.getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 6, 2));

        var confirmed = recurringService.confirm(new ConfirmRequest(
                rule.id(), LocalDate.of(2026, 6, 2), null, new BigDecimal("7600"), null));
        assertThat(confirmed.status()).isEqualTo(OccurrenceStatus.CONFIRMED);
        assertThat(confirmed.transactionId()).isNotNull();

        var transaction = transactionRepository.findById(confirmed.transactionId()).orElseThrow();
        assertThat(transaction.getAmount()).isEqualByComparingTo("7600");   // override wins
        assertThat(transaction.getDescription()).isEqualTo("Rent");
        assertThat(transaction.getCategory().getId()).isEqualTo(rent.getId());

        assertThat(recurringService.queue(today)).hasSize(1);

        assertThatThrownBy(() -> recurringService.confirm(new ConfirmRequest(
                rule.id(), LocalDate.of(2026, 6, 2), null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("skip removes from queue; deleting the skip re-opens it")
    void skipAndUnskip() {
        var rule = monthlyRentRule();
        var skipped = recurringService.skip(new SkipRequest(rule.id(), LocalDate.of(2026, 6, 2)));
        assertThat(recurringService.queue(today)).hasSize(1);

        recurringService.deleteOccurrence(skipped.id());
        assertThat(recurringService.queue(today)).hasSize(2);
    }

    @Test
    @DisplayName("forecast-only rule never enters the confirm queue")
    void forecastOnlyRuleNotInQueue() {
        recurringService.create(new RecurringRuleRequest(
                "Groceries", TransactionType.EXPENSE, account.getId(), null, rent.getId(),
                ScheduleFrequency.MONTHLY, 2, null, null,
                LocalDate.of(2026, 6, 1), null,
                AmountStrategy.FIXED, new BigDecimal("7500"), null, null, null, true), today);

        assertThat(recurringService.queue(today)).isEmpty();
    }

    @Test
    @DisplayName("non-schedule date is rejected")
    void nonScheduleDateRejected() {
        var rule = monthlyRentRule();
        assertThatThrownBy(() -> recurringService.confirm(new ConfirmRequest(
                rule.id(), LocalDate.of(2026, 6, 3), null, BigDecimal.ONE, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
