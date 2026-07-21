package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.model.TransactionFilter;
import org.zeveon.cashflows.model.dto.*;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.repository.spec.TransactionSpecifications;
import org.zeveon.cashflows.service.AggregationService;
import org.zeveon.cashflows.service.FxService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregates are computed in Java over the filtered set: volumes are tiny (NFR-6)
 * and per-date rate lookups live in the DB anyway.
 *
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class AggregationServiceImpl implements AggregationService {

    public static final String ORIGINAL = "ORIGINAL";

    private final TransactionRepository transactionRepository;
    private final FxService fxService;

    @Override
    @Transactional(readOnly = true)
    public DayTotalsResponse dayTotals(TransactionFilter filter, String currency) {
        var transactions = fetch(filter);
        if (ORIGINAL.equalsIgnoreCase(currency)) {
            return originalDayTotals(transactions);
        }
        var target = currency.toUpperCase();
        var incomplete = new boolean[]{false};
        Map<LocalDate, BigDecimal[]> byDay = new TreeMap<>();
        for (var transaction : transactions) {
            var converted = convertAtOwnDate(transaction, target, incomplete);
            if (converted == null) {
                continue;
            }
            var totals = byDay.computeIfAbsent(transaction.getTxnDate(),
                    d -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            accumulate(totals, transaction.getType(), converted);
        }
        var days = byDay.entrySet().stream()
                .map(entry -> new DayTotalDto(entry.getKey(),
                        scale(entry.getValue()[0]), scale(entry.getValue()[1]), null))
                .toList();
        return new DayTotalsResponse(target, incomplete[0], days);
    }

    @Override
    @Transactional(readOnly = true)
    public SummaryDto summary(TransactionFilter filter, LocalDate referenceDate, String currency) {
        var target = currency.toUpperCase();
        var weekStart = referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var weekEnd = weekStart.plusDays(6);
        var monthStart = referenceDate.withDayOfMonth(1);
        var monthEnd = referenceDate.with(TemporalAdjusters.lastDayOfMonth());
        var from = weekStart.isBefore(monthStart) ? weekStart : monthStart;
        var to = weekEnd.isAfter(monthEnd) ? weekEnd : monthEnd;

        var rangeFilter = new TransactionFilter(from, to, filter.accountId(), filter.categoryId(),
                filter.type(), filter.q(), filter.needsReview());
        var transactions = fetch(rangeFilter);

        var incomplete = new boolean[]{false};
        var day = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        var week = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        var month = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        for (var transaction : transactions) {
            var converted = convertAtOwnDate(transaction, target, incomplete);
            if (converted == null) {
                continue;
            }
            var date = transaction.getTxnDate();
            if (date.equals(referenceDate)) {
                accumulate(day, transaction.getType(), converted);
            }
            if (!date.isBefore(weekStart) && !date.isAfter(weekEnd)) {
                accumulate(week, transaction.getType(), converted);
            }
            if (!date.isBefore(monthStart) && !date.isAfter(monthEnd)) {
                accumulate(month, transaction.getType(), converted);
            }
        }
        return new SummaryDto(target, incomplete[0],
                new PeriodTotalsDto(scale(day[0]), scale(day[1])),
                new PeriodTotalsDto(scale(week[0]), scale(week[1])),
                new PeriodTotalsDto(scale(month[0]), scale(month[1])));
    }

    private List<Transaction> fetch(TransactionFilter filter) {
        // system categories (Correction) excluded: FR-12.3
        return transactionRepository
                .findAll(TransactionSpecifications.matching(filter, true)).stream()
                .filter(transaction -> transaction.getType() != TransactionType.TRANSFER)
                .toList();
    }

    /**
     * Converts the transaction amount from its account currency into the target,
     * with the rate table of the transaction's own date. Null (and the incomplete
     * flag) when no rate is available.
     */
    private BigDecimal convertAtOwnDate(Transaction transaction, String target, boolean[] incomplete) {
        var converted = fxService.convertAtDate(transaction.getAmount(),
                transaction.getAccount().getCurrency(), target, transaction.getTxnDate());
        if (converted.isEmpty()) {
            incomplete[0] = true;
            return null;
        }
        return converted.get();
    }

    private DayTotalsResponse originalDayTotals(List<Transaction> transactions) {
        Map<LocalDate, Map<String, BigDecimal[]>> byDay = new TreeMap<>();
        for (var transaction : transactions) {
            var totals = byDay
                    .computeIfAbsent(transaction.getTxnDate(), d -> new HashMap<>())
                    .computeIfAbsent(transaction.getAccount().getCurrency(),
                            c -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            accumulate(totals, transaction.getType(), transaction.getAmount());
        }
        var days = byDay.entrySet().stream()
                .map(entry -> new DayTotalDto(entry.getKey(), null, null,
                        entry.getValue().entrySet().stream()
                                .map(currencyEntry -> new CurrencyTotalDto(currencyEntry.getKey(),
                                        scale(currencyEntry.getValue()[0]), scale(currencyEntry.getValue()[1])))
                                .sorted((a, b) -> a.currency().compareTo(b.currency()))
                                .toList()))
                .toList();
        return new DayTotalsResponse(ORIGINAL, false, days);
    }

    private static void accumulate(BigDecimal[] totals, TransactionType type, BigDecimal amount) {
        if (type == TransactionType.EXPENSE) {
            totals[0] = totals[0].add(amount);
        } else {
            totals[1] = totals[1].add(amount);
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
