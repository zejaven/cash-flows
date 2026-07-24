package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.RecurringOccurrence;
import org.zeveon.cashflows.entity.RecurringRule;
import org.zeveon.cashflows.entity.enums.AveragingGranularity;
import org.zeveon.cashflows.entity.enums.DebtDirection;
import org.zeveon.cashflows.entity.enums.DebtEntryRole;
import org.zeveon.cashflows.entity.enums.DebtKind;
import org.zeveon.cashflows.entity.enums.PlannedStatus;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.model.TransactionFilter;
import org.zeveon.cashflows.model.dto.ForecastDtos.*;
import org.zeveon.cashflows.recurring.RecurringAmountCalculator;
import org.zeveon.cashflows.recurring.ScheduleCalculator;
import org.zeveon.cashflows.repository.*;
import org.zeveon.cashflows.repository.spec.TransactionSpecifications;
import org.zeveon.cashflows.service.ForecastService;
import org.zeveon.cashflows.service.FxService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class ForecastServiceImpl implements ForecastService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final RecurringRuleRepository ruleRepository;
    private final RecurringOccurrenceRepository occurrenceRepository;
    private final PlannedTransactionRepository plannedRepository;
    private final AveragingProfileRepository profileRepository;
    private final LoanScheduleItemRepository scheduleItemRepository;
    private final DebtRepository debtRepository;
    private final DebtEntryRepository debtEntryRepository;
    private final RecurringAmountCalculator amountCalculator;
    private final AveragingProfileServiceImpl averagingProfileService;
    private final FxService fxService;

    /** A raw balance change on a day; discrete events show up in the table. */
    private record Event(EventKind kind, Long refId, String label, Long accountId, BigDecimal delta,
                         Long counterAccountId, BigDecimal counterDelta, boolean approx,
                         boolean discrete) {
    }

    @Override
    @Transactional(readOnly = true)
    public ForecastResponse forecast(LocalDate today, int horizonMonths, String currency,
                                     LocalDate historyFrom) {
        var horizonEnd = today.plusMonths(horizonMonths);
        var accounts = accountRepository.findAll().stream()
                .filter(account -> !account.isArchived())
                .sorted(Comparator.comparing(Account::getId))
                .toList();
        var accountIds = accounts.stream().map(Account::getId).collect(Collectors.toSet());
        var currencyById = accounts.stream()
                .collect(Collectors.toMap(Account::getId, Account::getCurrency));
        var rates = fxService.currentRates().map(r -> r.rates()).orElse(null);

        // --- history series (one deltas query, no per-day SQL)
        var history = buildHistory(accounts, historyFrom, today, currency, rates);

        // --- future events
        var events = collectEvents(today, horizonEnd, accountIds);

        // --- daily simulation from the as-of-today anchor
        var balances = new HashMap<Long, BigDecimal>();
        accountRepository.findAllBalancesAsOf(today).forEach(entry -> {
            if (accountIds.contains(entry.getId())) {
                balances.put(entry.getId(), entry.getBalance());
            }
        });
        accounts.forEach(account ->
                balances.putIfAbsent(account.getId(), account.getStartBalance()));

        var dates = new ArrayList<LocalDate>();
        var series = new HashMap<Long, List<BigDecimal>>();
        accounts.forEach(account -> series.put(account.getId(), new ArrayList<>()));
        var totals = new ArrayList<BigDecimal>();
        var eventDays = new ArrayList<EventDay>();
        var firstNegativeByAccount = new LinkedHashMap<Long, LocalDate>();
        var approxTotal = new boolean[]{false};

        var dailyProfileItems = dailyProfileInfoItems(events);

        for (var date = today; !date.isAfter(horizonEnd); date = date.plusDays(1)) {
            var dayEvents = events.getOrDefault(date, List.of());
            for (var event : dayEvents) {
                balances.merge(event.accountId(), event.delta(), BigDecimal::add);
                if (event.counterAccountId() != null && event.counterDelta() != null) {
                    balances.merge(event.counterAccountId(), event.counterDelta(), BigDecimal::add);
                }
            }
            dates.add(date);
            for (var account : accounts) {
                var balance = balances.get(account.getId());
                series.get(account.getId()).add(scale(balance));
                if (balance.signum() < 0) {
                    firstNegativeByAccount.putIfAbsent(account.getId(), date);
                }
            }
            totals.add(totalInCurrency(balances, currencyById, currency, rates, approxTotal));

            var discrete = dayEvents.stream().filter(Event::discrete).toList();
            if (!discrete.isEmpty()) {
                var items = new ArrayList<EventItem>();
                discrete.forEach(event -> items.add(new EventItem(event.kind(), event.refId(),
                        event.label(), event.accountId(), scale(event.delta()),
                        event.counterAccountId(),
                        event.counterDelta() == null ? null : scale(event.counterDelta()),
                        event.approx())));
                items.addAll(dailyProfileItems);
                eventDays.add(new EventDay(date, items, snapshot(balances),
                        totals.getLast()));
            }
        }

        var negativeDates = firstNegativeByAccount.entrySet().stream()
                .map(entry -> new NegativeDate(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(NegativeDate::date))
                .toList();

        return new ForecastResponse(currency, approxTotal[0],
                accounts.stream().map(account -> new AccountRef(account.getId(), account.getName(),
                        account.getCurrency())).toList(),
                history,
                new Series(dates, series, totals),
                eventDays,
                negativeDates,
                negativeDates.isEmpty() ? null : negativeDates.getFirst());
    }

    // --- event collection ---

    private TreeMap<LocalDate, List<Event>> collectEvents(LocalDate today, LocalDate horizonEnd,
                                                          Set<Long> accountIds) {
        var events = new TreeMap<LocalDate, List<Event>>();

        // recurring: pending occurrences; overdue ones land on today (money leaves
        // as soon as the user confirms)
        var rules = ruleRepository.findByArchivedFalseOrderByIdAsc().stream()
                .filter(RecurringRule::isForecastVisible)
                .filter(rule -> accountIds.contains(rule.getAccount().getId()))
                .toList();
        var actedOnByRule = occurrenceRepository
                .findByRuleIdIn(rules.stream().map(RecurringRule::getId).toList()).stream()
                .collect(Collectors.groupingBy(occurrence -> occurrence.getRule().getId(),
                        Collectors.mapping(RecurringOccurrence::getDueDate, Collectors.toSet())));
        for (var rule : rules) {
            var current = amountCalculator.currentAmount(rule);
            if (current.value() == null) {
                continue;  // no basis to forecast; the queue asks for a manual amount
            }
            var actedOn = actedOnByRule.getOrDefault(rule.getId(), Set.of());
            // forecast-only rules never get acted on, so their overdue occurrences would
            // pile up on today — project them from today forward instead of the start date
            var from = rule.isForecastOnly() ? today : rule.getStartDate();
            for (var dueDate : ScheduleCalculator.pendingBetween(rule, actedOn, from, horizonEnd)) {
                var effectiveDate = dueDate.isBefore(today) ? today : dueDate;
                add(events, effectiveDate, recurringEvent(rule, current, accountIds));
            }
        }

        // planned PENDING
        for (var planned : plannedRepository.findByStatusOrderByDueDateAsc(PlannedStatus.PENDING)) {
            if (!planned.isForecastVisible()
                    || !accountIds.contains(planned.getAccount().getId())
                    || planned.getDueDate().isAfter(horizonEnd)) {
                continue;
            }
            var effectiveDate = planned.getDueDate().isBefore(today) ? today : planned.getDueDate();
            var delta = planned.getType() == TransactionType.INCOME
                    ? planned.getAmount() : planned.getAmount().negate();
            var counterId = planned.getCounterAccount() == null ? null
                    : planned.getCounterAccount().getId();
            add(events, effectiveDate, new Event(EventKind.PLANNED, planned.getId(),
                    planned.getDescription() == null ? "Planned" : planned.getDescription(),
                    planned.getAccount().getId(), delta,
                    accountIds.contains(counterId) ? counterId : null,
                    planned.getCounterAmount(), false, true));
        }

        // averaging profiles: DAILY drain from tomorrow; MONTHLY on future 1sts
        for (var profile : profileRepository.findByArchivedFalseOrderByIdAsc()) {
            if (!profile.isForecastVisible()
                    || !accountIds.contains(profile.getAccount().getId())) {
                continue;
            }
            var computed = averagingProfileService.computeFor(profile, today);
            var value = AveragingProfileServiceImpl.effectiveValue(profile, computed);
            if (value == null || value.signum() == 0) {
                continue;
            }
            if (profile.getGranularity() == AveragingGranularity.DAILY) {
                for (var date = today.plusDays(1); !date.isAfter(horizonEnd); date = date.plusDays(1)) {
                    add(events, date, new Event(EventKind.PROFILE_DAILY, profile.getId(),
                            profile.getName(), profile.getAccount().getId(), value.negate(),
                            null, null, computed.approx(), false));
                }
            } else {
                var month = today.withDayOfMonth(1).plusMonths(1);
                while (!month.isAfter(horizonEnd)) {
                    add(events, month, new Event(EventKind.PROFILE_MONTHLY, profile.getId(),
                            profile.getName() + " (monthly avg)", profile.getAccount().getId(),
                            value.negate(), null, null, computed.approx(), true));
                    month = month.plusMonths(1);
                }
            }
        }

        // loan payments come from the imported schedule (FR-13.5): exact dates and
        // amounts; unpaid overdue rows land on today like recurring occurrences
        for (var item : scheduleItemRepository.findPendingUpTo(horizonEnd)) {
            var debt = item.getDebt();
            var account = debt.getAccount();
            if (account == null || !accountIds.contains(account.getId())) {
                continue;
            }
            var effectiveDate = item.getDueDate().isBefore(today) ? today : item.getDueDate();
            var outgoing = debt.getDirection() == DebtDirection.I_OWE;
            add(events, effectiveDate, new Event(EventKind.LOAN_PAYMENT, item.getId(),
                    "%s · payment #%d".formatted(debt.getName(), item.getPosition()),
                    account.getId(),
                    outgoing ? item.getTotalAmount().negate() : item.getTotalAmount(),
                    null, null, false, true));
        }

        // personal debts expected back (FR-13.4): outstanding lands on the debt's
        // account at the expected return date, FX-estimated across currencies
        var sums = new HashMap<Long, Map<DebtEntryRole, BigDecimal>>();
        for (var sum : debtEntryRepository.sumPrincipalsByDebt()) {
            sums.computeIfAbsent(sum.getDebtId(), id -> new HashMap<>())
                    .put(sum.getRole(), sum.getTotal());
        }
        for (var debt : debtRepository.findByClosedFalseOrderByCreatedAtAsc()) {
            var account = debt.getAccount();
            if (debt.getKind() != DebtKind.PERSONAL || debt.getExpectedReturnDate() == null
                    || account == null || !accountIds.contains(account.getId())
                    || debt.getExpectedReturnDate().isAfter(horizonEnd)) {
                continue;
            }
            var byRole = sums.getOrDefault(debt.getId(), Map.of());
            var outstanding = DebtCalculator.outstanding(debt,
                    byRole.get(DebtEntryRole.ISSUE), byRole.get(DebtEntryRole.REPAYMENT));
            if (outstanding.signum() <= 0) {
                continue;
            }
            var approx = false;
            var amount = outstanding;
            if (!debt.getCurrency().equalsIgnoreCase(account.getCurrency())) {
                amount = fxService.currentRates()
                        .flatMap(current -> fxService.convert(outstanding, debt.getCurrency(),
                                account.getCurrency(), current.rates()))
                        .map(value -> value.setScale(2, RoundingMode.HALF_UP))
                        .orElse(null);
                if (amount == null) {
                    continue;  // no rate — better absent than a guess
                }
                approx = true;
            }
            var effectiveDate = debt.getExpectedReturnDate().isBefore(today)
                    ? today : debt.getExpectedReturnDate();
            var incoming = debt.getDirection() == DebtDirection.OWED_TO_ME;
            add(events, effectiveDate, new Event(EventKind.DEBT_REPAYMENT, debt.getId(),
                    debt.getName(), account.getId(),
                    incoming ? amount : amount.negate(),
                    null, null, approx, true));
        }

        // future-dated real transactions — the forecast must not diverge from them
        var future = transactionRepository.findAll(TransactionSpecifications.matching(
                new TransactionFilter(today.plusDays(1), horizonEnd, null, null, null, null, null)));
        for (var transaction : future) {
            if (!accountIds.contains(transaction.getAccount().getId())) {
                continue;
            }
            var delta = transaction.getType() == TransactionType.INCOME
                    ? transaction.getAmount() : transaction.getAmount().negate();
            var counterId = transaction.getCounterAccount() == null ? null
                    : transaction.getCounterAccount().getId();
            add(events, transaction.getTxnDate(), new Event(EventKind.TRANSACTION,
                    transaction.getId(),
                    transaction.getDescription() == null ? "Scheduled transaction"
                            : transaction.getDescription(),
                    transaction.getAccount().getId(), delta,
                    counterId != null && accountIds.contains(counterId) ? counterId : null,
                    transaction.getCounterAmount(), false, true));
        }

        return events;
    }

    private Event recurringEvent(RecurringRule rule, RecurringAmountCalculator.CurrentAmount current,
                                 Set<Long> accountIds) {
        var amount = current.value();
        var delta = rule.getType() == TransactionType.INCOME ? amount : amount.negate();
        Long counterId = null;
        BigDecimal counterDelta = null;
        if (rule.getType() == TransactionType.TRANSFER && rule.getCounterAccount() != null
                && accountIds.contains(rule.getCounterAccount().getId())) {
            counterId = rule.getCounterAccount().getId();
            counterDelta = estimateCounter(rule, amount);
        }
        return new Event(EventKind.RECURRING, rule.getId(), rule.getName(),
                rule.getAccount().getId(), delta, counterId, counterDelta,
                current.approx(), true);
    }

    private BigDecimal estimateCounter(RecurringRule rule, BigDecimal amount) {
        if (rule.getCounterAmount() != null) {
            return rule.getCounterAmount();
        }
        var from = rule.getAccount().getCurrency();
        var to = rule.getCounterAccount().getCurrency();
        if (from.equals(to)) {
            return amount;
        }
        return fxService.currentRates()
                .flatMap(current -> fxService.convert(amount, from, to, current.rates()))
                .map(value -> value.setScale(2, RoundingMode.HALF_UP))
                .orElse(null);
    }

    // --- history ---

    private Series buildHistory(List<Account> accounts, LocalDate from, LocalDate today,
                                String currency, Map<String, Double> rates) {
        var anchorDate = from.minusDays(1);
        var balances = new HashMap<Long, BigDecimal>();
        accountRepository.findAllBalancesAsOf(anchorDate).forEach(entry ->
                balances.put(entry.getId(), entry.getBalance()));
        accounts.forEach(account -> balances.putIfAbsent(account.getId(), account.getStartBalance()));

        Map<LocalDate, Map<Long, BigDecimal>> deltas = new HashMap<>();
        transactionRepository.findDailyDeltas(from, today).forEach(delta ->
                deltas.computeIfAbsent(delta.getDay(), d -> new HashMap<>())
                        .put(delta.getAccountId(), delta.getDelta()));

        var dates = new ArrayList<LocalDate>();
        var series = new HashMap<Long, List<BigDecimal>>();
        accounts.forEach(account -> series.put(account.getId(), new ArrayList<>()));
        var totals = new ArrayList<BigDecimal>();
        var approx = new boolean[]{false};
        var currencyById = accounts.stream()
                .collect(Collectors.toMap(Account::getId, Account::getCurrency));

        for (var date = from; !date.isAfter(today); date = date.plusDays(1)) {
            var dayDeltas = deltas.getOrDefault(date, Map.of());
            dayDeltas.forEach((accountId, delta) -> balances.merge(accountId, delta, BigDecimal::add));
            dates.add(date);
            for (var account : accounts) {
                series.get(account.getId()).add(scale(balances.get(account.getId())));
            }
            totals.add(totalInCurrency(balances, currencyById, currency, rates, approx));
        }
        return new Series(dates, series, totals);
    }

    // --- helpers ---

    private static void add(TreeMap<LocalDate, List<Event>> events, LocalDate date, Event event) {
        events.computeIfAbsent(date, d -> new ArrayList<>()).add(event);
    }

    private List<EventItem> dailyProfileInfoItems(TreeMap<LocalDate, List<Event>> events) {
        // one representative info item per daily profile ("avg daily −X/day")
        return events.values().stream()
                .flatMap(List::stream)
                .filter(event -> event.kind() == EventKind.PROFILE_DAILY)
                .collect(Collectors.toMap(Event::refId, event -> event, (a, b) -> a,
                        LinkedHashMap::new))
                .values().stream()
                .map(event -> new EventItem(EventKind.PROFILE_DAILY, event.refId(),
                        event.label() + " (daily avg)", event.accountId(), scale(event.delta()),
                        null, null, event.approx()))
                .toList();
    }

    private BigDecimal totalInCurrency(Map<Long, BigDecimal> balances,
                                       Map<Long, String> currencyById, String currency,
                                       Map<String, Double> rates, boolean[] approx) {
        var total = BigDecimal.ZERO;
        for (var entry : balances.entrySet()) {
            var from = currencyById.get(entry.getKey());
            if (from == null) {
                continue;
            }
            if (from.equals(currency)) {
                total = total.add(entry.getValue());
            } else if (rates != null) {
                var converted = fxService.convert(entry.getValue(), from, currency, rates);
                if (converted.isPresent()) {
                    total = total.add(converted.get());
                } else {
                    approx[0] = true;
                }
            } else {
                approx[0] = true;
            }
        }
        return scale(total);
    }

    private static Map<Long, BigDecimal> snapshot(Map<Long, BigDecimal> balances) {
        return balances.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> scale(entry.getValue())));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
