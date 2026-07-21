package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.zeveon.cashflows.entity.enums.AmountStrategy;
import org.zeveon.cashflows.entity.enums.OccurrenceStatus;
import org.zeveon.cashflows.entity.enums.ScheduleFrequency;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
public final class RecurringDtos {

    private RecurringDtos() {
    }

    public record RecurringRuleDto(
            Long id, String name, TransactionType type,
            Long accountId, String accountName, String accountCurrency,
            Long counterAccountId, String counterAccountName, String counterAccountCurrency,
            Long categoryId, String categoryName, String categoryColor,
            ScheduleFrequency scheduleFreq, Integer dayOfMonth, Integer dayOfWeek, Integer intervalDays,
            LocalDate startDate, LocalDate endDate,
            AmountStrategy amountStrategy, BigDecimal amount, BigDecimal counterAmount,
            Integer avgLookback, boolean archived,
            /* computed */ LocalDate nextDue, BigDecimal currentAmount, boolean approxAmount) {
    }

    public record RecurringRuleRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull TransactionType type,
            @NotNull Long accountId,
            Long counterAccountId,
            Long categoryId,
            @NotNull ScheduleFrequency scheduleFreq,
            Integer dayOfMonth,
            Integer dayOfWeek,
            Integer intervalDays,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @NotNull AmountStrategy amountStrategy,
            @Positive BigDecimal amount,
            @Positive BigDecimal counterAmount,
            Integer avgLookback,
            Boolean archived) {
    }

    public enum QueueKind {RECURRING, PLANNED}

    public record QueueItemDto(
            QueueKind kind,
            Long ruleId, Long plannedId,
            String name, LocalDate dueDate, TransactionType type,
            Long accountId, String accountName, String accountCurrency,
            Long counterAccountId, String counterAccountName, String counterAccountCurrency,
            BigDecimal suggestedAmount, BigDecimal suggestedCounterAmount, boolean approx) {
    }

    public record ConfirmRequest(@NotNull Long ruleId, @NotNull LocalDate dueDate,
                                 LocalDate date, @Positive BigDecimal amount,
                                 @Positive BigDecimal counterAmount) {
    }

    public record SkipRequest(@NotNull Long ruleId, @NotNull LocalDate dueDate) {
    }

    public record OccurrenceDto(Long id, Long ruleId, LocalDate dueDate, OccurrenceStatus status,
                                Long transactionId) {
    }
}
