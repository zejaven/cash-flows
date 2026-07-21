package org.zeveon.cashflows.model.dto;

import org.zeveon.cashflows.entity.enums.CategoryType;

/**
 * @author Stanislav Vafin
 */
public record CategoryDto(Long id, String name, CategoryType type, String color,
                          boolean system, boolean archived) {
}
