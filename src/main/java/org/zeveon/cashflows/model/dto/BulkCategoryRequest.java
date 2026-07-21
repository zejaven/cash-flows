package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public record BulkCategoryRequest(@NotEmpty List<Long> transactionIds, @NotNull Long categoryId) {
}
