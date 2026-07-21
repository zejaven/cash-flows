package org.zeveon.cashflows.model.dto;

import org.zeveon.cashflows.entity.enums.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
public record AccountDto(
        Long id,
        String name,
        String currency,
        AccountType type,
        Long bankId,
        String bankName,
        String accountNumber,
        BigDecimal startBalance,
        LocalDate startDate,
        boolean archived,
        BigDecimal balance) {
}
