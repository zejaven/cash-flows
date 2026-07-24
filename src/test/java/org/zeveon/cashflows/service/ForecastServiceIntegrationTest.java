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
import org.zeveon.cashflows.fx.model.FxRates;
import org.zeveon.cashflows.repository.*;
import org.zeveon.cashflows.service.impl.AveragingProfileServiceImpl;
import org.zeveon.cashflows.service.impl.ForecastServiceImpl;
import org.zeveon.cashflows.recurring.RecurringAmountCalculator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Full forecast simulation over multi-currency accounts, recurring transfer,
 * planned expense and a daily averaging profile. Compose postgres (cash_flows_test).
 *
 * @author Stanislav Vafin
 */
@Tag("integration")
@DataJpaTest
@Import({ForecastServiceImpl.class, AveragingProfileServiceImpl.class, RecurringAmountCalculator.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5434/cash_flows_test",
        "spring.datasource.username=${DB_USERNAME:cashflows}",
        "spring.datasource.password=${DB_PASSWORD:cashflows-local}",
})
class ForecastServiceIntegrationTest {

    private static final Map<String, Double> RATES = Map.of("USD", 1.0, "NZD", 2.0, "THB", 40.0);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 21);

    @Autowired
    private ForecastService forecastService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RecurringRuleRepository ruleRepository;

    @Autowired
    private RecurringOccurrenceRepository occurrenceRepository;

    @Autowired
    private PlannedTransactionRepository plannedRepository;

    @Autowired
    private AveragingProfileRepository profileRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CounterpartyRepository counterpartyRepository;

    @Autowired
    private DebtRepository debtRepository;

    @Autowired
    private DebtEntryRepository debtEntryRepository;

    @Autowired
    private LoanScheduleItemRepository scheduleItemRepository;

    @MockitoBean
    private FxService fxService;

    private Account usd;
    private Account nzd;

    @BeforeEach
    void setUp() {
        debtEntryRepository.deleteAll();
        scheduleItemRepository.deleteAll();
        debtRepository.deleteAll();
        counterpartyRepository.deleteAll();
        occurrenceRepository.deleteAll();
        ruleRepository.deleteAll();
        plannedRepository.deleteAll();
        profileRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        when(fxService.currentRates())
                .thenReturn(Optional.of(new FxRates("USD", RATES, Instant.parse("2026-07-21T00:00:00Z"))));
        when(fxService.ratesForDate(any())).thenReturn(Optional.of(RATES));
        when(fxService.convert(any(), any(), any(), any())).thenAnswer(invocation -> {
            BigDecimal amount = invocation.getArgument(0);
            String from = invocation.getArgument(1);
            String to = invocation.getArgument(2);
            var fromRate = RATES.get(from.equals("USDT") ? "USD" : from);
            var toRate = RATES.get(to.equals("USDT") ? "USD" : to);
            if (fromRate == null || toRate == null) return Optional.empty();
            return Optional.of(amount.multiply(BigDecimal.valueOf(toRate / fromRate)));
        });
        when(fxService.convertAtDate(any(), any(), any(), any())).thenAnswer(invocation -> {
            BigDecimal amount = invocation.getArgument(0);
            String from = invocation.getArgument(1);
            String to = invocation.getArgument(2);
            if (from.equals(to)) return Optional.of(amount);
            var fromRate = RATES.get(from.equals("USDT") ? "USD" : from);
            var toRate = RATES.get(to.equals("USDT") ? "USD" : to);
            if (fromRate == null || toRate == null) return Optional.empty();
            return Optional.of(amount.multiply(BigDecimal.valueOf(toRate / fromRate)));
        });

        usd = accountRepository.save(Account.builder()
                .name("Alatau USD").currency("USD").type(AccountType.BANK)
                .startBalance(new BigDecimal("1000")).startDate(LocalDate.of(2026, 1, 1))
                .archived(false).build());
        nzd = accountRepository.save(Account.builder()
                .name("Cash NZD").currency("NZD").type(AccountType.CASH)
                .startBalance(new BigDecimal("100")).startDate(LocalDate.of(2026, 1, 1))
                .archived(false).build());
    }

    @Test
    @DisplayName("recurring transfer moves both sides; planned and daily profile drain; negative date found")
    void fullSimulation() {
        var rent = categoryRepository.findByNameAndType("rent", CategoryType.EXPENSE).orElseThrow();
        var groceries = categoryRepository.findByNameAndType("groceries", CategoryType.EXPENSE)
                .orElseThrow();

        // recurring monthly transfer on the 1st: 200 USD -> 400 NZD (fixed counter)
        ruleRepository.save(RecurringRule.builder()
                .name("Top up cash").type(TransactionType.TRANSFER)
                .account(usd).counterAccount(nzd)
                .scheduleFreq(ScheduleFrequency.MONTHLY).dayOfMonth(1)
                .startDate(LocalDate.of(2026, 8, 1))
                .amountStrategy(AmountStrategy.FIXED)
                .amount(new BigDecimal("200")).counterAmount(new BigDecimal("400"))
                .archived(false).build());

        // planned one-off: 300 USD expense on Sep 12
        plannedRepository.save(PlannedTransaction.builder()
                .type(TransactionType.EXPENSE).dueDate(LocalDate.of(2026, 9, 12))
                .account(usd).amount(new BigDecimal("300")).category(rent)
                .description("Flight").status(PlannedStatus.PENDING).build());

        // grocery history for the profile: 20 NZD/day over 1..10 July
        for (int day = 1; day <= 10; day++) {
            transactionRepository.save(Transaction.builder()
                    .type(TransactionType.EXPENSE).txnDate(LocalDate.of(2026, 7, day))
                    .account(nzd).amount(new BigDecimal("20")).category(groceries)
                    .source(TransactionSource.MANUAL).build());
        }
        var profile = profileRepository.save(AveragingProfile.builder()
                .name("Groceries avg").account(nzd)
                .granularity(AveragingGranularity.DAILY)
                .windowMode(AveragingWindowMode.FIXED)
                .periodFrom(LocalDate.of(2026, 7, 1)).periodTo(LocalDate.of(2026, 7, 10))
                .categoryFilterMode(CategoryFilterMode.INCLUDE)
                .archived(false).build());
        profile.getCategories().add(groceries);
        profileRepository.save(profile);

        var response = forecastService.forecast(TODAY, 3, "USD", TODAY.minusMonths(1));

        // anchor: USD 1000, NZD 100 - 200 spent = -100? No: 10 days x 20 = 200 spent -> NZD = -100
        var nzdSeries = response.forecast().series().get(nzd.getId());
        var usdSeries = response.forecast().series().get(usd.getId());
        var dates = response.forecast().dates();

        assertThat(dates.getFirst()).isEqualTo(TODAY);
        assertThat(usdSeries.getFirst()).isEqualByComparingTo("1000");
        assertThat(nzdSeries.getFirst()).isEqualByComparingTo("-100");   // already negative today

        // negative date for NZD is today (history spending), and it is firstNegative
        assertThat(response.firstNegative()).isNotNull();
        assertThat(response.firstNegative().accountId()).isEqualTo(nzd.getId());
        assertThat(response.firstNegative().date()).isEqualTo(TODAY);

        // Aug 1: transfer applied to both sides.
        // USD: 1000 - 200 = 800; NZD: -100 - 20*11 (daily profile 22.07..01.08 incl.) + 400 = 80
        var aug1 = dates.indexOf(LocalDate.of(2026, 8, 1));
        assertThat(usdSeries.get(aug1)).isEqualByComparingTo("800");
        assertThat(nzdSeries.get(aug1)).isEqualByComparingTo("80");

        // Sep 12: planned expense on USD: 800 - 200 (Sep 1 transfer) - 300 = 300
        var sep12 = dates.indexOf(LocalDate.of(2026, 9, 12));
        assertThat(usdSeries.get(sep12)).isEqualByComparingTo("300");

        // eventDays contain the transfer with both deltas and the daily-profile info item
        var aug1Events = response.eventDays().stream()
                .filter(day -> day.date().equals(LocalDate.of(2026, 8, 1)))
                .findFirst().orElseThrow();
        assertThat(aug1Events.items())
                .anySatisfy(item -> {
                    assertThat(item.label()).isEqualTo("Top up cash");
                    assertThat(item.delta()).isEqualByComparingTo("-200");
                    assertThat(item.counterDelta()).isEqualByComparingTo("400");
                })
                .anySatisfy(item ->
                        assertThat(item.label()).contains("daily avg"));

        // total in USD on Aug 1: 800 + 80 NZD / 2 = 840
        assertThat(response.forecast().total().get(aug1)).isEqualByComparingTo("840");
        assertThat(response.approxTotal()).isFalse();
    }

    @Test
    @DisplayName("ROLLING profile averages the last N days ending today")
    void rollingWindowProfile() {
        var groceries = categoryRepository.findByNameAndType("groceries", CategoryType.EXPENSE)
                .orElseThrow();
        // 30 NZD/day for the 10 days ending today = 300 over the window
        for (int i = 0; i < 10; i++) {
            transactionRepository.save(Transaction.builder()
                    .type(TransactionType.EXPENSE).txnDate(TODAY.minusDays(i))
                    .account(nzd).amount(new BigDecimal("30")).category(groceries)
                    .source(TransactionSource.MANUAL).build());
        }
        var profile = profileRepository.save(AveragingProfile.builder()
                .name("Groceries rolling").account(nzd)
                .granularity(AveragingGranularity.DAILY)
                .windowMode(AveragingWindowMode.ROLLING).rollingDays(10)
                .categoryFilterMode(CategoryFilterMode.INCLUDE)
                .archived(false).build());
        profile.getCategories().add(groceries);
        profileRepository.save(profile);

        var response = forecastService.forecast(TODAY, 1, "NZD", TODAY);
        var nzdSeries = response.forecast().series().get(nzd.getId());
        var dates = response.forecast().dates();

        // only the daily profile drains the future: today->tomorrow delta == 300/10 = 30
        var today = dates.indexOf(TODAY);
        var tomorrow = dates.indexOf(TODAY.plusDays(1));
        assertThat(nzdSeries.get(today).subtract(nzdSeries.get(tomorrow)))
                .isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("forecast-hidden rules and profiles contribute nothing to the forecast")
    void hiddenContributorsExcluded() {
        var groceries = categoryRepository.findByNameAndType("groceries", CategoryType.EXPENSE)
                .orElseThrow();
        for (int i = 0; i < 10; i++) {
            transactionRepository.save(Transaction.builder()
                    .type(TransactionType.EXPENSE).txnDate(TODAY.minusDays(i))
                    .account(nzd).amount(new BigDecimal("30")).category(groceries)
                    .source(TransactionSource.MANUAL).build());
        }
        // would drain 30 NZD/day, but hidden from the forecast
        var profile = profileRepository.save(AveragingProfile.builder()
                .name("Hidden groceries").account(nzd)
                .granularity(AveragingGranularity.DAILY)
                .windowMode(AveragingWindowMode.ROLLING).rollingDays(10)
                .categoryFilterMode(CategoryFilterMode.INCLUDE)
                .forecastVisible(false).archived(false).build());
        profile.getCategories().add(groceries);
        profileRepository.save(profile);
        // would post 500 NZD on the 1st, but hidden from the forecast
        ruleRepository.save(RecurringRule.builder()
                .name("Hidden rent").type(TransactionType.EXPENSE).account(nzd)
                .scheduleFreq(ScheduleFrequency.MONTHLY).dayOfMonth(1).startDate(TODAY)
                .amountStrategy(AmountStrategy.FIXED).amount(new BigDecimal("500"))
                .forecastVisible(false).archived(false).build());

        var response = forecastService.forecast(TODAY, 1, "NZD", TODAY);
        var nzdSeries = response.forecast().series().get(nzd.getId());
        var dates = response.forecast().dates();

        // no future drain from the hidden profile: balance is flat day to day
        assertThat(nzdSeries.get(dates.indexOf(TODAY)))
                .isEqualByComparingTo(nzdSeries.get(dates.indexOf(TODAY.plusDays(1))));
        // the hidden rule produces no forecast event
        assertThat(response.eventDays().stream().flatMap(day -> day.items().stream())
                .noneMatch(item -> item.label().equals("Hidden rent"))).isTrue();
    }

    @Test
    @DisplayName("loan schedule payments and expected debt repayments enter the forecast")
    void debtEvents() {
        var counterparty = counterpartyRepository.save(Counterparty.builder().name("Bank").build());
        var loan = debtRepository.save(Debt.builder()
                .name("Mortgage").direction(DebtDirection.I_OWE).kind(DebtKind.LOAN)
                .counterparty(counterparty).currency("USD")
                .openingPrincipal(new BigDecimal("10000")).anchorDate(LocalDate.of(2026, 1, 1))
                .account(usd).build());
        // overdue unpaid row snaps to today; the future one stays on its date
        scheduleItemRepository.save(LoanScheduleItem.builder()
                .debt(loan).position(1).dueDate(LocalDate.of(2026, 7, 15))
                .principalAmount(new BigDecimal("90")).interestAmount(new BigDecimal("10"))
                .totalAmount(new BigDecimal("100")).build());
        scheduleItemRepository.save(LoanScheduleItem.builder()
                .debt(loan).position(2).dueDate(LocalDate.of(2026, 8, 17))
                .principalAmount(new BigDecimal("91")).interestAmount(new BigDecimal("9"))
                .totalAmount(new BigDecimal("100")).build());

        var lent = debtRepository.save(Debt.builder()
                .name("Friend").direction(DebtDirection.OWED_TO_ME).kind(DebtKind.PERSONAL)
                .counterparty(counterparty).currency("USD")
                .openingPrincipal(new BigDecimal("500")).anchorDate(LocalDate.of(2026, 1, 1))
                .expectedReturnDate(LocalDate.of(2026, 9, 1))
                .account(usd).build());

        var response = forecastService.forecast(TODAY, 3, "USD", TODAY.minusDays(7));
        var usdSeries = response.forecast().series().get(usd.getId());
        var dates = response.forecast().dates();

        // today: 1000 - 100 (overdue loan payment snapped to today)
        assertThat(usdSeries.getFirst()).isEqualByComparingTo("900");
        var aug17 = dates.indexOf(LocalDate.of(2026, 8, 17));
        assertThat(usdSeries.get(aug17)).isEqualByComparingTo("800");
        // Sep 1: friend's 500 comes back
        var sep1 = dates.indexOf(LocalDate.of(2026, 9, 1));
        assertThat(usdSeries.get(sep1)).isEqualByComparingTo("1300");

        assertThat(response.eventDays())
                .filteredOn(day -> day.date().equals(LocalDate.of(2026, 9, 1)))
                .first()
                .satisfies(day -> assertThat(day.items())
                        .anySatisfy(item -> {
                            assertThat(item.kind().name()).isEqualTo("DEBT_REPAYMENT");
                            assertThat(item.delta()).isEqualByComparingTo("500");
                        }));
        assertThat(response.eventDays().getFirst().items())
                .anySatisfy(item -> assertThat(item.kind().name()).isEqualTo("LOAN_PAYMENT"));

        // a paid row leaves the forecast
        var row2 = scheduleItemRepository.findByDebtIdOrderByDueDateAscPositionAsc(loan.getId())
                .getLast();
        var payment = transactionRepository.save(Transaction.builder()
                .type(TransactionType.EXPENSE).txnDate(LocalDate.of(2026, 8, 17))
                .account(usd).amount(new BigDecimal("100"))
                .source(TransactionSource.MANUAL).build());
        debtEntryRepository.save(DebtEntry.builder()
                .debt(loan).transaction(payment).role(DebtEntryRole.REPAYMENT)
                .txnPrincipal(new BigDecimal("91")).debtPrincipal(new BigDecimal("91"))
                .scheduleItem(row2).build());

        var after = forecastService.forecast(TODAY, 3, "USD", TODAY.minusDays(7));
        assertThat(after.eventDays())
                .flatMap(day -> day.items())
                .filteredOn(item -> item.kind().name().equals("LOAN_PAYMENT"))
                .hasSize(1);   // only the overdue row #1 remains pending
    }

    @Test
    @DisplayName("overdue pending occurrence lands on today")
    void overdueLandsOnToday() {
        var salary = categoryRepository.findByNameAndType("salary", CategoryType.INCOME).orElseThrow();
        ruleRepository.save(RecurringRule.builder()
                .name("Salary").type(TransactionType.INCOME)
                .account(usd).category(salary)
                .scheduleFreq(ScheduleFrequency.MONTHLY).dayOfMonth(20)
                .startDate(LocalDate.of(2026, 7, 1))
                .amountStrategy(AmountStrategy.FIXED).amount(new BigDecimal("500"))
                .archived(false).build());

        var response = forecastService.forecast(TODAY, 1, "USD", TODAY.minusDays(7));
        // July 20 was yesterday and unconfirmed -> applied today
        assertThat(response.forecast().series().get(usd.getId()).getFirst())
                .isEqualByComparingTo("1500");
        assertThat(response.eventDays().getFirst().date()).isEqualTo(TODAY);
    }
}
