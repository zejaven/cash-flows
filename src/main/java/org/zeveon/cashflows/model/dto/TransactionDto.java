package org.zeveon.cashflows.model.dto;

import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * @author Stanislav Vafin
 */
public record TransactionDto(
        Long id,
        TransactionType type,
        LocalDate date,
        LocalTime time,
        Long accountId,
        String accountName,
        String accountCurrency,
        BigDecimal amount,
        Long categoryId,
        String categoryName,
        String categoryColor,
        String description,
        String originalCurrency,
        BigDecimal originalAmount,
        Long counterAccountId,
        String counterAccountName,
        String counterAccountCurrency,
        BigDecimal counterAmount,
        TransactionSource source,
        String reference) {
}
