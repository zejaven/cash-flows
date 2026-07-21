package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.zeveon.cashflows.entity.enums.CategoryType;

/**
 * @author Stanislav Vafin
 */
public record CategoryRequest(
        @NotBlank @Size(max = 64) String name,
        @NotNull CategoryType type,
        @NotBlank @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "must be a #rrggbb color") String color,
        Boolean archived) {
}
