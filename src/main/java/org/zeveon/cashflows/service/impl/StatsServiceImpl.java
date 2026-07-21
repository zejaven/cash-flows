package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.AveragingGranularity;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.model.TransactionFilter;
import org.zeveon.cashflows.model.dto.StatsDtos.*;
import org.zeveon.cashflows.repository.AveragingProfileRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.repository.spec.TransactionSpecifications;
import org.zeveon.cashflows.service.FxService;
import org.zeveon.cashflows.service.StatsService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM uu");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("d MMM");
    private static final String UNCATEGORIZED = "uncategorized";

    private final TransactionRepository transactionRepository;
    private final AveragingProfileRepository profileRepository;
    private final AveragingProfileServiceImpl averagingProfileService;
    private final FxService fxService;

    @Override
    @Transactional(readOnly = true)
    public CategoriesResponse categories(LocalDate from, LocalDate to, Granularity granularity,
                                         String currency, boolean includeSystem) {
        var target = currency.toUpperCase();
        var transactions = transactionRepository.findAll(TransactionSpecifications.matching(
                        new TransactionFilter(from, to, null, null, TransactionType.EXPENSE, null, null),
                        !includeSystem)).stream()
                .filter(transaction -> transaction.getType() == TransactionType.EXPENSE)
                .toList();

        var incomplete = new boolean[]{false};
        // periodStart -> (categoryKey -> amount); category key null = uncategorized
        var buckets = new TreeMap<LocalDate, Map<Long, BigDecimal>>();
        var categoryMeta = new HashMap<Long, Transaction>();
        for (var transaction : transactions) {
            var converted = convert(transaction, target, incomplete);
            if (converted == null) {
                continue;
            }
            var periodStart = bucketStart(transaction.getTxnDate(), granularity);
            var categoryId = transaction.getCategory() == null ? null
                    : transaction.getCategory().getId();
            buckets.computeIfAbsent(periodStart, d -> new HashMap<>())
                    .merge(categoryId, converted, BigDecimal::add);
            if (categoryId != null) {
                categoryMeta.putIfAbsent(categoryId, transaction);
            }
        }

        // continuous axis: include empty buckets across the range
        var result = new ArrayList<Bucket>();
        for (var start = bucketStart(from, granularity); !start.isAfter(to);
             start = next(start, granularity)) {
            var end = bucketEnd(start, granularity);
            var byCategory = buckets.getOrDefault(start, Map.of());
            var rows = byCategory.entrySet().stream()
                    .map(entry -> {
                        if (entry.getKey() == null) {
                            return new CategoryAmount(null, UNCATEGORIZED, null, scale(entry.getValue()));
                        }
                        var category = categoryMeta.get(entry.getKey()).getCategory();
                        return new CategoryAmount(category.getId(), category.getName(),
                                category.getColor(), scale(entry.getValue()));
                    })
                    .sorted(Comparator.comparing(CategoryAmount::amount).reversed())
                    .toList();
            var total = rows.stream().map(CategoryAmount::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new Bucket(start, end, label(start, end, granularity), scale(total), rows));
        }
        return new CategoriesResponse(target, incomplete[0], result);
    }

    @Override
    @Transactional(readOnly = true)
    public FactVsForecast factVsForecast(LocalDate from, LocalDate to, String currency) {
        var target = currency.toUpperCase();
        var approx = new boolean[]{false};

        // forecast: what the averaging profiles predicted for this period (FR-8.2 semantics)
        var days = ChronoUnit.DAYS.between(from, to) + 1;
        var firstOfMonths = countFirstOfMonths(from, to);
        var forecast = BigDecimal.ZERO;
        for (var profile : profileRepository.findByArchivedFalseOrderByIdAsc()) {
            var value = AveragingProfileServiceImpl.effectiveValue(profile,
                    averagingProfileService.computeFor(profile));
            if (value == null || value.signum() == 0) {
                continue;
            }
            var periodTotal = profile.getGranularity() == AveragingGranularity.DAILY
                    ? value.multiply(BigDecimal.valueOf(days))
                    : value.multiply(BigDecimal.valueOf(firstOfMonths));
            var converted = fxService.convertAtDate(periodTotal,
                    profile.getAccount().getCurrency(), target, to);
            if (converted.isEmpty()) {
                approx[0] = true;
                continue;
            }
            if (!profile.getAccount().getCurrency().equals(target)) {
                approx[0] = true;
            }
            forecast = forecast.add(converted.get());
        }

        // actual: expenses of the period, system categories excluded
        var actual = BigDecimal.ZERO;
        var transactions = transactionRepository.findAll(TransactionSpecifications.matching(
                new TransactionFilter(from, to, null, null, TransactionType.EXPENSE, null, null),
                true));
        for (var transaction : transactions) {
            var converted = convert(transaction, target, approx);
            if (converted != null) {
                actual = actual.add(converted);
            }
        }

        var diff = forecast.subtract(actual);
        var diffPct = forecast.signum() == 0 ? null
                : diff.divide(forecast, java.math.MathContext.DECIMAL64)
                .multiply(new BigDecimal("100"))
                .setScale(1, RoundingMode.HALF_UP);
        return new FactVsForecast(target, scale(forecast), scale(actual), scale(diff),
                diffPct, approx[0]);
    }

    // --- helpers ---

    private BigDecimal convert(Transaction transaction, String target, boolean[] incomplete) {
        var converted = fxService.convertAtDate(transaction.getAmount(),
                transaction.getAccount().getCurrency(), target, transaction.getTxnDate());
        if (converted.isEmpty()) {
            incomplete[0] = true;
            return null;
        }
        return converted.get();
    }

    private static LocalDate bucketStart(LocalDate date, Granularity granularity) {
        return granularity == Granularity.MONTH
                ? date.withDayOfMonth(1)
                : date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate bucketEnd(LocalDate start, Granularity granularity) {
        return granularity == Granularity.MONTH
                ? start.with(TemporalAdjusters.lastDayOfMonth())
                : start.plusDays(6);
    }

    private static LocalDate next(LocalDate start, Granularity granularity) {
        return granularity == Granularity.MONTH ? start.plusMonths(1) : start.plusWeeks(1);
    }

    private static String label(LocalDate start, LocalDate end, Granularity granularity) {
        return granularity == Granularity.MONTH
                ? MONTH_LABEL.format(start)
                : DAY_LABEL.format(start) + " – " + DAY_LABEL.format(end);
    }

    private static long countFirstOfMonths(LocalDate from, LocalDate to) {
        var count = 0L;
        for (var date = from.withDayOfMonth(1); !date.isAfter(to); date = date.plusMonths(1)) {
            if (!date.isBefore(from)) {
                count++;
            }
        }
        return count;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
