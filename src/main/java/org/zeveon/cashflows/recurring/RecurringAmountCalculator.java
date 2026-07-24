package org.zeveon.cashflows.recurring;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.zeveon.cashflows.entity.RecurringRule;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.FxService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Current amount of a rule, in the rule account's currency (FR-7.2). Each source
 * transaction is converted with its own-date rate table (any source, any account —
 * utility payments arrive via statement import).
 * <ul>
 *   <li>{@code FIXED} — the stored amount.</li>
 *   <li>{@code AVERAGE} — mean of the last N actual transactions of the rule's
 *       type+category; per occurrence.</li>
 *   <li>{@code AVERAGE_PERIOD} — total spend over the last N days divided by N (a
 *       daily rate), scaled to the schedule interval. Sparse purchases are spread
 *       evenly across the window, so a daily forecast reflects real per-day spend
 *       rather than per-trip spend.</li>
 * </ul>
 *
 * @author Stanislav Vafin
 */
@Component
@RequiredArgsConstructor
public class RecurringAmountCalculator {

    /** Days used to scale AVERAGE_PERIOD for MONTHLY schedules (no calendar anchor here). */
    private static final int MONTHLY_DAYS = 30;

    private final TransactionRepository transactionRepository;
    private final FxService fxService;

    public record CurrentAmount(BigDecimal value, boolean approx) {

        public static final CurrentAmount UNKNOWN = new CurrentAmount(null, false);
    }

    /**
     * @param today reference date for the AVERAGE_PERIOD lookback window; unused by
     *              the FIXED and AVERAGE strategies.
     */
    public CurrentAmount currentAmount(RecurringRule rule, LocalDate today) {
        return switch (rule.getAmountStrategy()) {
            case FIXED -> new CurrentAmount(rule.getAmount(), false);
            case AVERAGE -> averageOfLastPayments(rule);
            case AVERAGE_PERIOD -> averagePerPeriod(rule, today);
        };
    }

    private CurrentAmount averageOfLastPayments(RecurringRule rule) {
        if (rule.getCategory() == null) {
            return CurrentAmount.UNKNOWN;
        }
        var lookback = rule.getAvgLookback() == null ? 3 : rule.getAvgLookback();
        var payments = transactionRepository.findByTypeAndCategoryIdOrderByTxnDateDescIdDesc(
                rule.getType(), rule.getCategory().getId(), PageRequest.of(0, lookback));
        var converted = convert(payments, rule.getAccount().getCurrency());
        if (converted.values().isEmpty()) {
            return CurrentAmount.UNKNOWN;
        }
        var average = converted.sum()
                .divide(BigDecimal.valueOf(converted.values().size()), 2, RoundingMode.HALF_UP);
        return new CurrentAmount(average, converted.approx());
    }

    private CurrentAmount averagePerPeriod(RecurringRule rule, LocalDate today) {
        if (rule.getCategory() == null) {
            return CurrentAmount.UNKNOWN;
        }
        var periodDays = rule.getAvgLookback() == null ? MONTHLY_DAYS : rule.getAvgLookback();
        var from = today.minusDays(periodDays - 1L);
        var payments = transactionRepository.findByTypeAndCategoryIdAndTxnDateBetween(
                rule.getType(), rule.getCategory().getId(), from, today);
        var converted = convert(payments, rule.getAccount().getCurrency());
        if (converted.values().isEmpty()) {
            return CurrentAmount.UNKNOWN;  // no basis in the window — the queue asks for a manual amount
        }
        // spread the whole window's spend over every day, then scale to one occurrence
        var perOccurrence = converted.sum()
                .divide(BigDecimal.valueOf(periodDays), 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(scheduleDays(rule)))
                .setScale(2, RoundingMode.HALF_UP);
        return new CurrentAmount(perOccurrence, converted.approx());
    }

    /** Days one occurrence covers: the gap between consecutive schedule dates. */
    private static int scheduleDays(RecurringRule rule) {
        return switch (rule.getScheduleFreq()) {
            case EVERY_N_DAYS -> rule.getIntervalDays() == null ? 1 : rule.getIntervalDays();
            case WEEKLY -> 7;
            case MONTHLY -> MONTHLY_DAYS;
        };
    }

    /**
     * Converts each payment into {@code targetCurrency}; any cross-currency payment
     * (converted or silently dropped for a missing rate) marks the result approximate.
     */
    private Converted convert(List<Transaction> payments, String targetCurrency) {
        var values = new ArrayList<BigDecimal>();
        var approx = false;
        for (var payment : payments) {
            var from = payment.getAccount().getCurrency();
            if (from.equals(targetCurrency)) {
                values.add(payment.getAmount());
                continue;
            }
            fxService.ratesForDate(payment.getTxnDate())
                    .flatMap(rates -> fxService.convert(payment.getAmount(), from, targetCurrency, rates))
                    .ifPresent(values::add);
            approx = true;   // cross-currency estimate (or a dropped payment) — mark it
        }
        return new Converted(values, approx);
    }

    private record Converted(List<BigDecimal> values, boolean approx) {

        BigDecimal sum() {
            return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}
