package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.CategoryRule;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.model.dto.CategoryRuleDtos.*;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.CategoryRuleRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.CategoryRuleService;
import org.zeveon.cashflows.statement.CategoryRuleMatcher;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class CategoryRuleServiceImpl implements CategoryRuleService {

    private final CategoryRuleRepository ruleRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryRuleDto> findAll() {
        return ruleRepository.findAll().stream()
                .map(CategoryRuleServiceImpl::toDto)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryRuleSaveResult create(CategoryRuleRequest request) {
        var category = resolveCategory(request.categoryId());
        var rule = ruleRepository.save(CategoryRule.builder()
                .pattern(request.pattern().trim())
                .category(category)
                .archived(Boolean.TRUE.equals(request.archived()))
                .build());
        var applied = request.applyToExisting() ? applyRule(rule) : 0;
        return new CategoryRuleSaveResult(toDto(rule), applied);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryRuleSaveResult update(Long id, CategoryRuleRequest request) {
        var rule = ruleRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Category rule", id));
        rule.setPattern(request.pattern().trim());
        rule.setCategory(resolveCategory(request.categoryId()));
        if (request.archived() != null) {
            rule.setArchived(request.archived());
        }
        var applied = request.applyToExisting() && !rule.isArchived() ? applyRule(rule) : 0;
        return new CategoryRuleSaveResult(toDto(rule), applied);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var rule = ruleRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Category rule", id));
        ruleRepository.delete(rule);
    }

    @Override
    @Transactional(readOnly = true)
    public RulePreview preview(String pattern, Long categoryId) {
        var category = resolveCategory(categoryId);
        var matcher = matcherFor(pattern, category);
        var count = (int) needsReview().stream()
                .filter(transaction -> matcher
                        .match(transaction.getDescription(), transaction.getType())
                        .isPresent())
                .count();
        return new RulePreview(count);
    }

    /** Applies one rule to current needs-review transactions; returns how many it categorized. */
    private int applyRule(CategoryRule rule) {
        var matcher = matcherFor(rule.getPattern(), rule.getCategory());
        var applied = 0;
        for (var transaction : needsReview()) {
            if (matcher.match(transaction.getDescription(), transaction.getType()).isPresent()) {
                transaction.setCategory(rule.getCategory());
                applied++;
            }
        }
        return applied;
    }

    private List<Transaction> needsReview() {
        return transactionRepository.findByCategoryIsNullAndTypeNot(TransactionType.TRANSFER);
    }

    private static CategoryRuleMatcher matcherFor(String pattern, Category category) {
        return CategoryRuleMatcher.of(List.of(CategoryRule.builder()
                .pattern(pattern)
                .category(category)
                .build()));
    }

    private Category resolveCategory(Long categoryId) {
        var category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> NotFoundException.of("Category", categoryId));
        if (category.isSystem()) {
            throw new ConflictException("Rules cannot target a system category");
        }
        return category;
    }

    private static CategoryRuleDto toDto(CategoryRule rule) {
        return new CategoryRuleDto(rule.getId(), rule.getPattern(), rule.getCategory().getId(),
                rule.getCategory().getName(), rule.getCategory().getType(),
                rule.getCategory().getColor(), rule.isArchived());
    }
}
