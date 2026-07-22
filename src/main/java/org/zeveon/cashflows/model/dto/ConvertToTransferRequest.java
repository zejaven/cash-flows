package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Turns a mis-parsed EXPENSE/INCOME into a TRANSFER (FR-2.2). When the opposite
 * side of the transfer was imported as its own transaction, {@code counterTransactionId}
 * names it: that row is absorbed (deleted) and its import key kept on the transfer,
 * so re-imports of overlapping periods stay idempotent (FR-4.5).
 *
 * @author Stanislav Vafin
 */
public record ConvertToTransferRequest(
        @NotNull LocalDate date,
        @NotNull Long accountId,
        @NotNull @Positive BigDecimal amount,
        @NotNull Long counterAccountId,
        @NotNull @Positive BigDecimal counterAmount,
        @Size(max = 512) String description,
        Long counterTransactionId) {
}
