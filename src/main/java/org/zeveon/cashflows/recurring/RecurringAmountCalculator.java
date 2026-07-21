package org.zeveon.cashflows.recurring;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.zeveon.cashflows.entity.RecurringRule;
import org.zeveon.cashflows.entity.enums.AmountStrategy;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.FxService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;

/**
 * Current amount of a rule: FIXED as stored; AVERAGE = mean of the last N actual
 * transactions of the rule's own type+category (any source, any account — utility
 * payments arrive via statement import), each converted into the rule account's
 * currency with its own-date rate table (FR-7.2).
 *
 * @author Stanislav Vafin
 */
@Component
@RequiredArgsConstructor
public class RecurringAmountCalculator {

    private final TransactionRepository transactionRepository;
    private final FxService fxService;

    public record CurrentAmount(BigDecimal value, boolean approx) {

        public static final CurrentAmount UNKNOWN = new CurrentAmount(null, false);
    }

    public CurrentAmount currentAmount(RecurringRule rule) {
        if (rule.getAmountStrategy() == AmountStrategy.FIXED) {
            return new CurrentAmount(rule.getAmount(), false);
        }
        if (rule.getCategory() == null) {
            return CurrentAmount.UNKNOWN;
        }
        var lookback = rule.getAvgLookback() == null ? 3 : rule.getAvgLookback();
        var lastPayments = transactionRepository.findByTypeAndCategoryIdOrderByTxnDateDescIdDesc(
                rule.getType(), rule.getCategory().getId(), PageRequest.of(0, lookback));
        if (lastPayments.isEmpty()) {
            return CurrentAmount.UNKNOWN;
        }
        var targetCurrency = rule.getAccount().getCurrency();
        var converted = new ArrayList<BigDecimal>();
        var approx = false;
        for (var payment : lastPayments) {
            var from = payment.getAccount().getCurrency();
            if (from.equals(targetCurrency)) {
                converted.add(payment.getAmount());
                continue;
            }
            var value = fxService.ratesForDate(payment.getTxnDate())
                    .flatMap(rates -> fxService.convert(payment.getAmount(), from, targetCurrency, rates));
            if (value.isPresent()) {
                converted.add(value.get());
                approx = true;
            } else {
                approx = true;   // silently dropped a payment — mark the estimate
            }
        }
        if (converted.isEmpty()) {
            return CurrentAmount.UNKNOWN;
        }
        var sum = converted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        var average = sum.divide(BigDecimal.valueOf(converted.size()), 2, RoundingMode.HALF_UP);
        return new CurrentAmount(average, approx);
    }
}
