package org.zeveon.cashflows.statement;

import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.CategoryRule;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic rule matching (FR-4.7): a pattern is a {@code |}-separated set of
 * case-insensitive substrings; the first rule (by id order of the input list)
 * whose category type matches the transaction direction wins. No guessing —
 * no match means "needs review".
 *
 * @author Stanislav Vafin
 */
public class CategoryRuleMatcher {

    private record CompiledRule(Category category, List<String> needles) {
    }

    private final List<CompiledRule> rules;

    private CategoryRuleMatcher(List<CompiledRule> rules) {
        this.rules = rules;
    }

    /** Input must be non-archived rules ordered by id ascending. */
    public static CategoryRuleMatcher of(List<CategoryRule> categoryRules) {
        var compiled = categoryRules.stream()
                .filter(rule -> !rule.getCategory().isArchived())
                .map(rule -> new CompiledRule(rule.getCategory(),
                        Arrays.stream(rule.getPattern().split("\\|"))
                                .map(String::trim)
                                .map(String::toLowerCase)
                                .filter(needle -> !needle.isEmpty())
                                .toList()))
                .filter(rule -> !rule.needles().isEmpty())
                .toList();
        return new CategoryRuleMatcher(compiled);
    }

    public Optional<Category> match(String description, TransactionType type) {
        if (description == null || description.isBlank() || type == TransactionType.TRANSFER) {
            return Optional.empty();
        }
        var haystack = description.toLowerCase();
        var wanted = type == TransactionType.EXPENSE ? CategoryType.EXPENSE : CategoryType.INCOME;
        return rules.stream()
                .filter(rule -> rule.category().getType() == wanted)
                .filter(rule -> rule.needles().stream().anyMatch(haystack::contains))
                .map(CompiledRule::category)
                .findFirst();
    }
}
