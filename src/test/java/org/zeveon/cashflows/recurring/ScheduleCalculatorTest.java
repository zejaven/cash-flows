package org.zeveon.cashflows.recurring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zeveon.cashflows.entity.RecurringRule;
import org.zeveon.cashflows.entity.enums.ScheduleFrequency;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stanislav Vafin
 */
class ScheduleCalculatorTest {

    private static RecurringRule monthly(int day, LocalDate start, LocalDate end) {
        return RecurringRule.builder()
                .scheduleFreq(ScheduleFrequency.MONTHLY)
                .dayOfMonth(day)
                .startDate(start)
                .endDate(end)
                .build();
    }

    @Test
    @DisplayName("MONTHLY day 31 clamps to Feb 28 / Apr 30 and returns to the 31st without drift")
    void monthlyDay31NoDrift() {
        var rule = monthly(31, LocalDate.of(2026, 1, 1), null);
        var dates = ScheduleCalculator.datesBetween(rule,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31));
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("MONTHLY day 31 hits Feb 29 in a leap year")
    void monthlyLeapFebruary() {
        var rule = monthly(31, LocalDate.of(2028, 1, 1), null);
        var dates = ScheduleCalculator.datesBetween(rule,
                LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29));
        assertThat(dates).containsExactly(LocalDate.of(2028, 2, 29));
    }

    @Test
    @DisplayName("MONTHLY respects start and end bounds")
    void monthlyBounds() {
        var rule = monthly(15, LocalDate.of(2026, 3, 20), LocalDate.of(2026, 6, 1));
        var dates = ScheduleCalculator.datesBetween(rule,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        // March 15 is before start; June 15 is after end
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 4, 15),
                LocalDate.of(2026, 5, 15));
    }

    @Test
    @DisplayName("WEEKLY crosses a year boundary")
    void weeklyAcrossYear() {
        var rule = RecurringRule.builder()
                .scheduleFreq(ScheduleFrequency.WEEKLY)
                .dayOfWeek(1)  // Monday
                .startDate(LocalDate.of(2026, 12, 20))
                .build();
        var dates = ScheduleCalculator.datesBetween(rule,
                LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 12));
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 12, 21),
                LocalDate.of(2026, 12, 28),
                LocalDate.of(2027, 1, 4),
                LocalDate.of(2027, 1, 11));
    }

    @Test
    @DisplayName("EVERY_N_DAYS stays anchored to start_date regardless of the query window")
    void everyNDaysAnchor() {
        var rule = RecurringRule.builder()
                .scheduleFreq(ScheduleFrequency.EVERY_N_DAYS)
                .intervalDays(10)
                .startDate(LocalDate.of(2026, 1, 1))
                .build();
        // window starts mid-cycle: dates must still be 1, 11, 21, 31... aligned
        var dates = ScheduleCalculator.datesBetween(rule,
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 1));
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 1, 21),
                LocalDate.of(2026, 1, 31));
    }

    @Test
    @DisplayName("pending = schedule minus acted-on, gaps in the middle stay open")
    void pendingSubtractsActedOn() {
        var rule = monthly(2, LocalDate.of(2026, 1, 1), null);
        var actedOn = Set.of(
                LocalDate.of(2026, 1, 2),   // confirmed
                LocalDate.of(2026, 3, 2));  // confirmed out of order
        var pending = ScheduleCalculator.pendingBetween(rule, actedOn,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30));
        // February stayed open even though March was already confirmed
        assertThat(pending).containsExactly(
                LocalDate.of(2026, 2, 2),
                LocalDate.of(2026, 4, 2));
    }

    @Test
    @DisplayName("isScheduleDate validates a single day")
    void isScheduleDate() {
        var rule = monthly(31, LocalDate.of(2026, 1, 1), null);
        assertThat(ScheduleCalculator.isScheduleDate(rule, LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(ScheduleCalculator.isScheduleDate(rule, LocalDate.of(2026, 3, 30))).isFalse();
    }
}
