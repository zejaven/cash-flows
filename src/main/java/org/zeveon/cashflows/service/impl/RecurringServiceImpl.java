package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.*;
import org.zeveon.cashflows.entity.enums.*;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.model.dto.RecurringDtos.*;
import org.zeveon.cashflows.recurring.RecurringAmountCalculator;
import org.zeveon.cashflows.recurring.ScheduleCalculator;
import org.zeveon.cashflows.repository.*;
import org.zeveon.cashflows.service.FxService;
import org.zeveon.cashflows.service.RecurringService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class RecurringServiceImpl implements RecurringService {

    private final RecurringRuleRepository ruleRepository;
    private final RecurringOccurrenceRepository occurrenceRepository;
    private final PlannedTransactionRepository plannedRepository;
    private final LoanScheduleItemRepository scheduleItemRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final RecurringAmountCalculator amountCalculator;
    private final FxService fxService;

    @Override
    @Transactional(readOnly = true)
    public List<RecurringRuleDto> findAll(LocalDate today) {
        return ruleRepository.findAll().stream()
                .sorted(Comparator.comparing(RecurringRule::getId))
                .map(rule -> toDto(rule, today))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecurringRuleDto create(RecurringRuleRequest request, LocalDate today) {
        validate(request);
        var rule = new RecurringRule();
        apply(rule, request);
        return toDto(ruleRepository.save(rule), today);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecurringRuleDto update(Long id, RecurringRuleRequest request, LocalDate today) {
        validate(request);
        var rule = ruleRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Recurring rule", id));
        apply(rule, request);
        return toDto(rule, today);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecurringRuleDto setForecastVisible(Long id, boolean visible, LocalDate today) {
        var rule = ruleRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Recurring rule", id));
        rule.setForecastVisible(visible);
        return toDto(rule, today);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var rule = ruleRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Recurring rule", id));
        if (occurrenceRepository.existsByRuleId(id)) {
            throw new ConflictException("Rule has confirmed/skipped occurrences — archive it instead");
        }
        ruleRepository.delete(rule);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueueItemDto> queue(LocalDate today) {
        var items = new ArrayList<QueueItemDto>();

        // forecast-only rules opt out of the confirm queue (they still project in the forecast)
        var rules = ruleRepository.findByArchivedFalseOrderByIdAsc().stream()
                .filter(rule -> !rule.getAccount().isArchived() && !rule.isForecastOnly())
                .toList();
        var actedOnByRule = occurrenceRepository
                .findByRuleIdIn(rules.stream().map(RecurringRule::getId).toList()).stream()
                .collect(Collectors.groupingBy(occurrence -> occurrence.getRule().getId(),
                        Collectors.mapping(RecurringOccurrence::getDueDate, Collectors.toSet())));
        for (var rule : rules) {
            var actedOn = actedOnByRule.getOrDefault(rule.getId(), java.util.Set.of());
            var current = amountCalculator.currentAmount(rule);
            for (var dueDate : ScheduleCalculator.pendingBetween(rule, actedOn, rule.getStartDate(), today)) {
                items.add(new QueueItemDto(QueueKind.RECURRING, rule.getId(), null,
                        rule.getName(), dueDate, rule.getType(),
                        rule.getAccount().getId(), rule.getAccount().getName(),
                        rule.getAccount().getCurrency(),
                        rule.getCounterAccount() == null ? null : rule.getCounterAccount().getId(),
                        rule.getCounterAccount() == null ? null : rule.getCounterAccount().getName(),
                        rule.getCounterAccount() == null ? null : rule.getCounterAccount().getCurrency(),
                        current.value(), estimateCounter(rule, current.value()), current.approx(),
                        null, null));
            }
        }

        for (var planned : plannedRepository.findByStatusOrderByDueDateAsc(PlannedStatus.PENDING)) {
            if (planned.getDueDate().isAfter(today) || planned.getAccount().isArchived()) {
                continue;
            }
            items.add(new QueueItemDto(QueueKind.PLANNED, null, planned.getId(),
                    planned.getDescription() == null ? "Planned" : planned.getDescription(),
                    planned.getDueDate(), planned.getType(),
                    planned.getAccount().getId(), planned.getAccount().getName(),
                    planned.getAccount().getCurrency(),
                    planned.getCounterAccount() == null ? null : planned.getCounterAccount().getId(),
                    planned.getCounterAccount() == null ? null : planned.getCounterAccount().getName(),
                    planned.getCounterAccount() == null ? null : planned.getCounterAccount().getCurrency(),
                    planned.getAmount(), planned.getCounterAmount(), false, null, null));
        }

        // due loan payments (FR-13.7): the imported schedule is the source, not a rule
        for (var item : scheduleItemRepository.findPendingUpTo(today)) {
            var debt = item.getDebt();
            var account = debt.getAccount();
            items.add(new QueueItemDto(QueueKind.LOAN, null, null,
                    "%s · payment #%d".formatted(debt.getName(), item.getPosition()),
                    item.getDueDate(),
                    DebtServiceImpl.expectedType(debt.getDirection(), DebtEntryRole.REPAYMENT),
                    account == null ? null : account.getId(),
                    account == null ? null : account.getName(),
                    account == null ? null : account.getCurrency(),
                    null, null, null,
                    item.getTotalAmount(), null, false,
                    debt.getId(), item.getId()));
        }

        items.sort(Comparator.comparing(QueueItemDto::dueDate));
        return items;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OccurrenceDto confirm(ConfirmRequest request) {
        var rule = ruleRepository.findById(request.ruleId())
                .orElseThrow(() -> NotFoundException.of("Recurring rule", request.ruleId()));
        if (!ScheduleCalculator.isScheduleDate(rule, request.dueDate())) {
            throw new IllegalArgumentException("%s is not a schedule date of '%s'"
                    .formatted(request.dueDate(), rule.getName()));
        }
        occurrenceRepository.findByRuleIdAndDueDate(rule.getId(), request.dueDate())
                .ifPresent(existing -> {
                    throw new ConflictException("Occurrence already " + existing.getStatus());
                });

        var amount = request.amount() != null ? request.amount()
                : amountCalculator.currentAmount(rule).value();
        if (amount == null) {
            throw new IllegalArgumentException(
                    "No amount: the rule has no payment history yet — enter it explicitly");
        }
        BigDecimal counterAmount = null;
        if (rule.getType() == TransactionType.TRANSFER) {
            counterAmount = request.counterAmount() != null ? request.counterAmount()
                    : estimateCounter(rule, amount);
            if (counterAmount == null) {
                throw new IllegalArgumentException(
                        "No counter amount and no FX estimate available — enter it explicitly");
            }
        }

        var transaction = transactionRepository.save(Transaction.builder()
                .type(rule.getType())
                .txnDate(request.date() != null ? request.date() : request.dueDate())
                .account(rule.getAccount())
                .amount(amount)
                .category(rule.getCategory())
                .description(rule.getName())
                .counterAccount(rule.getType() == TransactionType.TRANSFER ? rule.getCounterAccount() : null)
                .counterAmount(counterAmount)
                .source(TransactionSource.MANUAL)
                .build());
        var occurrence = occurrenceRepository.save(RecurringOccurrence.builder()
                .rule(rule)
                .dueDate(request.dueDate())
                .status(OccurrenceStatus.CONFIRMED)
                .transaction(transaction)
                .build());
        return toDto(occurrence);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OccurrenceDto skip(SkipRequest request) {
        var rule = ruleRepository.findById(request.ruleId())
                .orElseThrow(() -> NotFoundException.of("Recurring rule", request.ruleId()));
        if (!ScheduleCalculator.isScheduleDate(rule, request.dueDate())) {
            throw new IllegalArgumentException("%s is not a schedule date of '%s'"
                    .formatted(request.dueDate(), rule.getName()));
        }
        occurrenceRepository.findByRuleIdAndDueDate(rule.getId(), request.dueDate())
                .ifPresent(existing -> {
                    throw new ConflictException("Occurrence already " + existing.getStatus());
                });
        var occurrence = occurrenceRepository.save(RecurringOccurrence.builder()
                .rule(rule)
                .dueDate(request.dueDate())
                .status(OccurrenceStatus.SKIPPED)
                .build());
        return toDto(occurrence);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OccurrenceDto> occurrences(Long ruleId) {
        return occurrenceRepository.findByRuleId(ruleId).stream()
                .sorted(Comparator.comparing(RecurringOccurrence::getDueDate).reversed())
                .map(RecurringServiceImpl::toDto)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOccurrence(Long occurrenceId) {
        var occurrence = occurrenceRepository.findById(occurrenceId)
                .orElseThrow(() -> NotFoundException.of("Occurrence", occurrenceId));
        // un-skip is always safe; a confirmed occurrence may only be re-opened after
        // its transaction is gone (FK SET NULL) — otherwise money would double-count
        if (occurrence.getStatus() == OccurrenceStatus.CONFIRMED && occurrence.getTransaction() != null) {
            throw new ConflictException("Delete the created transaction first");
        }
        occurrenceRepository.delete(occurrence);
    }

    // --- helpers ---

    private void validate(RecurringRuleRequest request) {
        var isTransfer = request.type() == TransactionType.TRANSFER;
        if (isTransfer) {
            if (request.counterAccountId() == null) {
                throw new IllegalArgumentException("Transfer rule needs counterAccountId");
            }
            if (request.counterAccountId().equals(request.accountId())) {
                throw new IllegalArgumentException("Transfer must go to a different account");
            }
            if (request.categoryId() != null) {
                throw new IllegalArgumentException("Transfer rule cannot have a category");
            }
            if (request.amountStrategy() == AmountStrategy.AVERAGE) {
                throw new IllegalArgumentException(
                        "Transfer rules are FIXED-only (no category to average over)");
            }
        } else {
            if (request.categoryId() == null) {
                throw new IllegalArgumentException("Expense/income rule needs a category");
            }
            if (request.counterAccountId() != null) {
                throw new IllegalArgumentException("Counter account is only for transfers");
            }
        }
        switch (request.scheduleFreq()) {
            case MONTHLY -> require(request.dayOfMonth() != null
                    && request.dayOfMonth() >= 1 && request.dayOfMonth() <= 31,
                    "MONTHLY needs dayOfMonth 1..31");
            case WEEKLY -> require(request.dayOfWeek() != null
                    && request.dayOfWeek() >= 1 && request.dayOfWeek() <= 7,
                    "WEEKLY needs dayOfWeek 1..7 (ISO)");
            case EVERY_N_DAYS -> require(request.intervalDays() != null && request.intervalDays() >= 1,
                    "EVERY_N_DAYS needs intervalDays >= 1");
        }
        if (request.amountStrategy() == AmountStrategy.FIXED && request.amount() == null) {
            throw new IllegalArgumentException("FIXED strategy needs an amount");
        }
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("endDate is before startDate");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void apply(RecurringRule rule, RecurringRuleRequest request) {
        rule.setName(request.name());
        rule.setType(request.type());
        rule.setAccount(accountRepository.findById(request.accountId())
                .orElseThrow(() -> NotFoundException.of("Account", request.accountId())));
        rule.setCounterAccount(request.counterAccountId() == null ? null
                : accountRepository.findById(request.counterAccountId())
                .orElseThrow(() -> NotFoundException.of("Account", request.counterAccountId())));
        rule.setCategory(resolveCategory(request));
        rule.setScheduleFreq(request.scheduleFreq());
        rule.setDayOfMonth(request.scheduleFreq() == ScheduleFrequency.MONTHLY ? request.dayOfMonth() : null);
        rule.setDayOfWeek(request.scheduleFreq() == ScheduleFrequency.WEEKLY ? request.dayOfWeek() : null);
        rule.setIntervalDays(request.scheduleFreq() == ScheduleFrequency.EVERY_N_DAYS
                ? request.intervalDays() : null);
        rule.setStartDate(request.startDate());
        rule.setEndDate(request.endDate());
        rule.setAmountStrategy(request.amountStrategy());
        rule.setAmount(request.amountStrategy() == AmountStrategy.FIXED ? request.amount() : null);
        rule.setCounterAmount(request.type() == TransactionType.TRANSFER ? request.counterAmount() : null);
        rule.setAvgLookback(request.amountStrategy() == AmountStrategy.AVERAGE
                ? (request.avgLookback() == null ? 3 : request.avgLookback()) : null);
        if (request.archived() != null) {
            rule.setArchived(request.archived());
        }
        if (request.forecastOnly() != null) {
            rule.setForecastOnly(request.forecastOnly());
        }
    }

    private Category resolveCategory(RecurringRuleRequest request) {
        if (request.categoryId() == null) {
            return null;
        }
        var category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> NotFoundException.of("Category", request.categoryId()));
        var expected = request.type() == TransactionType.EXPENSE
                ? CategoryType.EXPENSE : CategoryType.INCOME;
        if (category.getType() != expected) {
            throw new ConflictException("Category '%s' is %s, rule is %s"
                    .formatted(category.getName(), category.getType(), request.type()));
        }
        if (category.isSystem()) {
            throw new ConflictException("Rules cannot use a system category");
        }
        return category;
    }

    /**
     * Estimated receiving amount of a transfer: fixed counterAmount, same value
     * for same-currency accounts, else current-FX cross estimate.
     */
    private BigDecimal estimateCounter(RecurringRule rule, BigDecimal amount) {
        if (rule.getType() != TransactionType.TRANSFER || amount == null) {
            return null;
        }
        if (rule.getCounterAmount() != null) {
            return rule.getCounterAmount();
        }
        var from = rule.getAccount().getCurrency();
        var to = rule.getCounterAccount().getCurrency();
        if (from.equals(to)) {
            return amount;
        }
        return fxService.currentRates()
                .flatMap(rates -> fxService.convert(amount, from, to, rates.rates()))
                .map(value -> value.setScale(2, RoundingMode.HALF_UP))
                .orElse(null);
    }

    private RecurringRuleDto toDto(RecurringRule rule, LocalDate today) {
        var current = amountCalculator.currentAmount(rule);
        var nextDue = rule.isArchived() ? null
                : ScheduleCalculator.datesBetween(rule, today, today.plusYears(2)).stream()
                .findFirst()
                .orElse(null);
        return new RecurringRuleDto(rule.getId(), rule.getName(), rule.getType(),
                rule.getAccount().getId(), rule.getAccount().getName(), rule.getAccount().getCurrency(),
                rule.getCounterAccount() == null ? null : rule.getCounterAccount().getId(),
                rule.getCounterAccount() == null ? null : rule.getCounterAccount().getName(),
                rule.getCounterAccount() == null ? null : rule.getCounterAccount().getCurrency(),
                rule.getCategory() == null ? null : rule.getCategory().getId(),
                rule.getCategory() == null ? null : rule.getCategory().getName(),
                rule.getCategory() == null ? null : rule.getCategory().getColor(),
                rule.getScheduleFreq(), rule.getDayOfMonth(), rule.getDayOfWeek(), rule.getIntervalDays(),
                rule.getStartDate(), rule.getEndDate(),
                rule.getAmountStrategy(), rule.getAmount(), rule.getCounterAmount(),
                rule.getAvgLookback(), rule.isArchived(), rule.isForecastOnly(),
                rule.isForecastVisible(),
                nextDue, current.value(), current.approx());
    }

    private static OccurrenceDto toDto(RecurringOccurrence occurrence) {
        return new OccurrenceDto(occurrence.getId(), occurrence.getRule().getId(),
                occurrence.getDueDate(), occurrence.getStatus(),
                occurrence.getTransaction() == null ? null : occurrence.getTransaction().getId());
    }
}
