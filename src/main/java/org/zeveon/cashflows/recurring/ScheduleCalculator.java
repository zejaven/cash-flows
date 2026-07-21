package org.zeveon.cashflows.recurring;

import org.zeveon.cashflows.entity.RecurringRule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure schedule-date generation. MONTHLY pins the configured day-of-month:
 * day 31 clamps to the end of shorter months (Feb 28/29) and returns to the
 * 31st afterwards — no drift, because the parameter is stored, not derived
 * from the previous occurrence.
 *
 * @author Stanislav Vafin
 */
public final class ScheduleCalculator {

    private ScheduleCalculator() {
    }

    /**
     * All schedule dates of the rule within [from, to], inclusive, bounded by the
     * rule's own start/end dates.
     */
    public static List<LocalDate> datesBetween(RecurringRule rule, LocalDate from, LocalDate to) {
        var lower = max(from, rule.getStartDate());
        var upper = rule.getEndDate() == null ? to : min(to, rule.getEndDate());
        if (lower.isAfter(upper)) {
            return List.of();
        }
        return switch (rule.getScheduleFreq()) {
            case MONTHLY -> monthlyDates(rule.getDayOfMonth(), lower, upper);
            case WEEKLY -> weeklyDates(rule.getDayOfWeek(), lower, upper);
            case EVERY_N_DAYS -> everyNDates(rule.getStartDate(), rule.getIntervalDays(), lower, upper);
        };
    }

    /**
     * Pending occurrences: schedule dates minus already acted-on due dates (FR-7.3).
     * No "resume after the latest acted-on" shortcut — the queue allows confirming
     * occurrences in any order, leaving older ones open.
     */
    public static List<LocalDate> pendingBetween(RecurringRule rule, Set<LocalDate> actedOn,
                                                 LocalDate from, LocalDate to) {
        return datesBetween(rule, from, to).stream()
                .filter(date -> !actedOn.contains(date))
                .toList();
    }

    public static boolean isScheduleDate(RecurringRule rule, LocalDate date) {
        return !datesBetween(rule, date, date).isEmpty();
    }

    private static List<LocalDate> monthlyDates(int dayOfMonth, LocalDate lower, LocalDate upper) {
        var dates = new ArrayList<LocalDate>();
        var month = YearMonth.from(lower);
        var last = YearMonth.from(upper);
        while (!month.isAfter(last)) {
            var date = month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
            if (!date.isBefore(lower) && !date.isAfter(upper)) {
                dates.add(date);
            }
            month = month.plusMonths(1);
        }
        return dates;
    }

    private static List<LocalDate> weeklyDates(int isoDayOfWeek, LocalDate lower, LocalDate upper) {
        var dates = new ArrayList<LocalDate>();
        var date = lower.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(isoDayOfWeek)));
        while (!date.isAfter(upper)) {
            dates.add(date);
            date = date.plusWeeks(1);
        }
        return dates;
    }

    private static List<LocalDate> everyNDates(LocalDate anchor, int intervalDays,
                                               LocalDate lower, LocalDate upper) {
        var dates = new ArrayList<LocalDate>();
        // first anchor-aligned date >= lower
        var daysBetween = java.time.temporal.ChronoUnit.DAYS.between(anchor, lower);
        var steps = daysBetween <= 0 ? 0 : (daysBetween + intervalDays - 1) / intervalDays;
        var date = anchor.plusDays(steps * intervalDays);
        while (!date.isAfter(upper)) {
            dates.add(date);
            date = date.plusDays(intervalDays);
        }
        return dates;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
