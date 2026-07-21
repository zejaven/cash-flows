package org.zeveon.cashflows.model;

import org.zeveon.cashflows.entity.enums.TransactionType;

import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
public record TransactionFilter(
        LocalDate from,
        LocalDate to,
        Long accountId,
        Long categoryId,
        TransactionType type,
        String q,
        Boolean needsReview) {
}
