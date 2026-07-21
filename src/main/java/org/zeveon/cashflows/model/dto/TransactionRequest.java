package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Type-dependent validation (counter fields, category type) lives in the service.
 *
 * @author Stanislav Vafin
 */
public record TransactionRequest(
        @NotNull TransactionType type,
        @NotNull LocalDate date,
        @NotNull Long accountId,
        @NotNull @Positive BigDecimal amount,
        Long categoryId,
        @Size(max = 512) String description,
        @Size(max = 8) String originalCurrency,
        @Positive BigDecimal originalAmount,
        Long counterAccountId,
        @Positive BigDecimal counterAmount) {
}
