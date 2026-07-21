package org.zeveon.cashflows.model.dto;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public record PageDto<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
}
