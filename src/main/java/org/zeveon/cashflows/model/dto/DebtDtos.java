package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.zeveon.cashflows.entity.enums.DebtDirection;
import org.zeveon.cashflows.entity.enums.DebtEntryRole;
import org.zeveon.cashflows.entity.enums.DebtKind;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
public final class DebtDtos {

    private DebtDtos() {
    }

    public record DebtDto(
            Long id, String name, DebtDirection direction, DebtKind kind,
            Long counterpartyId, String counterpartyName,
            String currency, BigDecimal openingPrincipal, LocalDate anchorDate,
            LocalDate expectedReturnDate,
            Long accountId, String accountName, String accountCurrency,
            Long interestCategoryId, String interestCategoryName,
            String note, boolean closed,
            /* computed */ BigDecimal outstanding, BigDecimal totalPrincipal, BigDecimal totalRepaid,
            LocalDate nextPaymentDate, BigDecimal nextPaymentAmount) {
    }

    public record DebtRequest(
            @NotBlank @Size(max = 128) String name,
            @NotNull DebtDirection direction,
            @NotNull DebtKind kind,
            @NotNull Long counterpartyId,
            @NotBlank @Size(max = 8) String currency,
            @NotNull @PositiveOrZero BigDecimal openingPrincipal,
            @NotNull LocalDate anchorDate,
            LocalDate expectedReturnDate,
            Long accountId,
            Long interestCategoryId,
            @Size(max = 512) String note) {
    }

    public record DebtSummaryDto(String currency, BigDecimal owedToMe, BigDecimal iOwe,
                                 boolean approx) {
    }

    public record DebtEntryDto(
            Long id, Long debtId, DebtEntryRole role,
            BigDecimal txnPrincipal, BigDecimal debtPrincipal, Long scheduleItemId,
            Long transactionId, LocalDate txnDate, BigDecimal txnAmount, String txnCurrency,
            String accountName, String description) {
    }

    /**
     * Attaches an existing transaction to the debt. With {@code scheduleItemId}
     * the role is REPAYMENT and principals default from the schedule row;
     * otherwise principals default to the full transaction amount.
     */
    public record DebtLinkRequest(
            @NotNull Long transactionId,
            DebtEntryRole role,
            Long scheduleItemId,
            @Positive BigDecimal txnPrincipal,
            @Positive BigDecimal debtPrincipal) {
    }

    /**
     * Creates a MANUAL transaction and its debt entry in one step.
     */
    public record DebtRecordRequest(
            @NotNull DebtEntryRole role,
            @NotNull LocalDate date,
            @NotNull Long accountId,
            @NotNull @Positive BigDecimal amount,
            @Positive BigDecimal txnPrincipal,
            @Positive BigDecimal debtPrincipal,
            @Size(max = 512) String description) {
    }

    public record LoanScheduleItemDto(
            Long id, int position, LocalDate dueDate,
            BigDecimal principalAmount, BigDecimal interestAmount, BigDecimal totalAmount,
            BigDecimal remainingAfter, boolean skipped, boolean paid, Long transactionId) {
    }

    public record LoanConfirmRequest(
            @NotNull Long scheduleItemId,
            LocalDate date,
            Long accountId,
            @Positive BigDecimal amount,
            @Positive BigDecimal txnPrincipal) {
    }

    public record ScheduleImportResult(int inserted, int replaced, int keptPaid) {
    }
}
