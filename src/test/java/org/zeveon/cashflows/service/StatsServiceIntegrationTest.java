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
import org.zeveon.cashflows.entity.*;
import org.zeveon.cashflows.entity.enums.*;
import org.zeveon.cashflows.model.dto.StatsDtos.Granularity;
import org.zeveon.cashflows.repository.*;
import org.zeveon.cashflows.service.impl.AveragingProfileServiceImpl;
import org.zeveon.cashflows.service.impl.FxServiceImpl;
import org.zeveon.cashflows.service.impl.StatsServiceImpl;
import org.zeveon.cashflows.fx.FxFacade;
import org.zeveon.cashflows.fx.ExchangeRateApi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Uses the REAL FxServiceImpl over stored snapshots (not a mock) — the point is
 * to prove buckets convert with each transaction's own-date rate table.
 * Compose postgres (cash_flows_test), tagged integration.
 *
 * @author Stanislav Vafin
 */
@Tag("integration")
@DataJpaTest
@Import({StatsServiceImpl.class, AveragingProfileServiceImpl.class, StatsServiceIntegrationTest.RealFxConfig.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5434/cash_flows_test",
        "spring.datasource.username=${DB_USERNAME:cashflows}",
        "spring.datasource.password=${DB_PASSWORD:cashflows-local}",
})
class StatsServiceIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class RealFxConfig {
        @org.springframework.context.annotation.Bean
        FxFacade fxFacade() {
            // network facade is irrelevant: snapshots exist for all test dates
            return new FxFacade(mock(ExchangeRateApi.class));
        }

        @org.springframework.context.annotation.Bean
        FxService fxService(FxFacade fxFacade, FxRateSnapshotRepository snapshotRepository) {
            return new FxServiceImpl(fxFacade, snapshotRepository);
        }
    }

    @Autowired
    private StatsService statsService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AveragingProfileRepository profileRepository;

    @Autowired
    private FxRateSnapshotRepository snapshotRepository;

    @Autowired
    private RecurringOccurrenceRepository occurrenceRepository;

    @Autowired
    private RecurringRuleRepository ruleRepository;

    @Autowired
    private PlannedTransactionRepository plannedRepository;

    private Account nzd;
    private Account usd;
    private Category groceries;
    private Category rent;

    @BeforeEach
    void setUp() {
        occurrenceRepository.deleteAll();
        ruleRepository.deleteAll();
        plannedRepository.deleteAll();
        profileRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        snapshotRepository.deleteAll();

        nzd = accountRepository.save(account("Cash NZD", "NZD"));
        usd = accountRepository.save(account("Alatau USD", "USD"));
        groceries = categoryRepository.findByNameAndType("groceries", CategoryType.EXPENSE).orElseThrow();
        rent = categoryRepository.findByNameAndType("rent", CategoryType.EXPENSE).orElseThrow();

        // different USD->NZD rates in June (2.0) and July (1.5): own-date conversion is observable
        snapshotRepository.save(snapshot(LocalDate.of(2026, 6, 1), Map.of("USD", 1.0, "NZD", 2.0)));
        snapshotRepository.save(snapshot(LocalDate.of(2026, 7, 1), Map.of("USD", 1.0, "NZD", 1.5)));
    }

    private static Account account(String name, String currency) {
        return Account.builder().name(name).currency(currency).type(AccountType.CASH)
                .startBalance(BigDecimal.ZERO).startDate(LocalDate.of(2026, 1, 1))
                .archived(false).build();
    }

    private static FxRateSnapshot snapshot(LocalDate date, Map<String, Double> rates) {
        return FxRateSnapshot.builder().snapshotDate(date).baseCurrency("USD")
                .rates(rates).fetchedAt(Instant.parse("2026-06-01T00:00:00Z")).build();
    }

    private void expense(Account account, Category category, String amount, LocalDate date) {
        transactionRepository.save(Transaction.builder()
                .type(TransactionType.EXPENSE).txnDate(date).account(account)
                .amount(new BigDecimal(amount)).category(category)
                .source(TransactionSource.MANUAL).build());
    }

    @Test
    @DisplayName("monthly buckets convert each transaction with its own month's rate")
    void bucketsUseOwnDateRates() {
        expense(nzd, groceries, "100", LocalDate.of(2026, 6, 10));
        expense(usd, rent, "50", LocalDate.of(2026, 6, 15));      // June: 50 USD -> 100 NZD
        expense(usd, rent, "50", LocalDate.of(2026, 7, 15));      // July: 50 USD -> 75 NZD
        expense(nzd, null, "20", LocalDate.of(2026, 7, 20));      // uncategorized

        var response = statsService.categories(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31),
                Granularity.MONTH, "NZD", false);

        assertThat(response.incomplete()).isFalse();
        assertThat(response.buckets()).hasSize(2);

        var june = response.buckets().getFirst();
        assertThat(june.total()).isEqualByComparingTo("200");     // 100 + 100
        assertThat(june.byCategory()).hasSize(2);

        var july = response.buckets().getLast();
        assertThat(july.total()).isEqualByComparingTo("95");      // 75 + 20
        assertThat(july.byCategory())
                .anySatisfy(row -> {
                    assertThat(row.categoryId()).isNull();
                    assertThat(row.name()).isEqualTo("uncategorized");
                    assertThat(row.amount()).isEqualByComparingTo("20");
                });
    }

    @Test
    @DisplayName("Correction hidden by default, shown with includeSystem")
    void correctionToggle() {
        var correction = categoryRepository
                .findByNameAndTypeAndSystemTrue("Correction", CategoryType.EXPENSE).orElseThrow();
        expense(nzd, groceries, "100", LocalDate.of(2026, 7, 5));
        expense(nzd, correction, "500", LocalDate.of(2026, 7, 6));

        var hidden = statsService.categories(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                Granularity.MONTH, "NZD", false);
        assertThat(hidden.buckets().getFirst().total()).isEqualByComparingTo("100");

        var shown = statsService.categories(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                Granularity.MONTH, "NZD", true);
        assertThat(shown.buckets().getFirst().total()).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("weekly buckets are Monday-based and the axis is continuous")
    void weeklyBuckets() {
        expense(nzd, groceries, "70", LocalDate.of(2026, 7, 8));  // Wed of week 6–12 Jul
        var response = statsService.categories(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 26),
                Granularity.WEEK, "NZD", false);
        assertThat(response.buckets()).hasSize(3);                // continuous incl. empty weeks
        assertThat(response.buckets().getFirst().periodStart()).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(response.buckets().getFirst().total()).isEqualByComparingTo("70");
        assertThat(response.buckets().get(1).total()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("fact vs forecast: DAILY×days + MONTHLY×first-of-months vs actual spending")
    void factVsForecast() {
        // profile history: 10 NZD/day groceries over 1–10 June
        for (int day = 1; day <= 10; day++) {
            expense(nzd, groceries, "10", LocalDate.of(2026, 6, day));
        }
        var daily = profileRepository.save(AveragingProfile.builder()
                .name("Daily").account(nzd).granularity(AveragingGranularity.DAILY)
                .periodFrom(LocalDate.of(2026, 6, 1)).periodTo(LocalDate.of(2026, 6, 10))
                .categoryFilterMode(CategoryFilterMode.INCLUDE).archived(false).build());
        daily.getCategories().add(groceries);
        profileRepository.save(daily);
        var monthly = profileRepository.save(AveragingProfile.builder()
                .name("Utilities").account(nzd).granularity(AveragingGranularity.MONTHLY)
                .periodFrom(LocalDate.of(2026, 6, 1)).periodTo(LocalDate.of(2026, 6, 30))
                .categoryFilterMode(CategoryFilterMode.INCLUDE)
                .manualOverride(new BigDecimal("90"))
                .archived(false).build());
        profileRepository.save(monthly);

        // actual July spending: 250 NZD
        expense(nzd, groceries, "250", LocalDate.of(2026, 7, 10));

        var result = statsService.factVsForecast(LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31), "NZD");

        // forecast = 10/day × 31 days + 90 × 1 first-of-month = 400
        assertThat(result.forecast()).isEqualByComparingTo("400");
        assertThat(result.actual()).isEqualByComparingTo("250");
        assertThat(result.diff()).isEqualByComparingTo("150");
        assertThat(result.diffPct()).isEqualByComparingTo("37.5");
    }
}
