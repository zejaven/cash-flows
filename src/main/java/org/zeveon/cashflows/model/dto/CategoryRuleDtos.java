package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.zeveon.cashflows.entity.enums.CategoryType;

/**
 * @author Stanislav Vafin
 */
public final class CategoryRuleDtos {

    private CategoryRuleDtos() {
    }

    public record CategoryRuleDto(Long id, String pattern, Long categoryId, String categoryName,
                                  CategoryType categoryType, String categoryColor, boolean archived) {
    }

    public record CategoryRuleRequest(@NotBlank @Size(max = 512) String pattern,
                                      @NotNull Long categoryId,
                                      Boolean archived,
                                      boolean applyToExisting) {
    }

    /** {@code appliedCount} — how many needs-review transactions the rule just categorized. */
    public record CategoryRuleSaveResult(CategoryRuleDto rule, int appliedCount) {
    }

    public record RulePreview(int matchCount) {
    }
}
