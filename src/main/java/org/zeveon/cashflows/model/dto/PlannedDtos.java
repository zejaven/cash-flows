package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.zeveon.cashflows.entity.enums.PlannedStatus;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
public final class PlannedDtos {

    private PlannedDtos() {
    }

    public record PlannedDto(
            Long id, TransactionType type, LocalDate dueDate,
            Long accountId, String accountName, String accountCurrency,
            Long counterAccountId, String counterAccountName, String counterAccountCurrency,
            BigDecimal amount, BigDecimal counterAmount,
            Long categoryId, String categoryName, String categoryColor,
            String description, PlannedStatus status, Long transactionId) {
    }

    public record PlannedRequest(
            @NotNull TransactionType type,
            @NotNull LocalDate dueDate,
            @NotNull Long accountId,
            Long counterAccountId,
            @NotNull @Positive BigDecimal amount,
            @Positive BigDecimal counterAmount,
            Long categoryId,
            @Size(max = 512) String description) {
    }

    public record PlannedConfirmRequest(LocalDate date, @Positive BigDecimal amount,
                                        @Positive BigDecimal counterAmount) {
    }
}
