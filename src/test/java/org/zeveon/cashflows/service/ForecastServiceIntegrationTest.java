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

    @MockitoBean
    private FxService fxService;

    private Account usd;
    private Account nzd;

    @BeforeEach
    void setUp() {
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
