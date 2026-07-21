package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.CategoryRuleDtos.CategoryRuleDto;
import org.zeveon.cashflows.model.dto.CategoryRuleDtos.CategoryRuleRequest;
import org.zeveon.cashflows.model.dto.CategoryRuleDtos.CategoryRuleSaveResult;
import org.zeveon.cashflows.model.dto.CategoryRuleDtos.RulePreview;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface CategoryRuleService {

    List<CategoryRuleDto> findAll();

    CategoryRuleSaveResult create(CategoryRuleRequest request);

    CategoryRuleSaveResult update(Long id, CategoryRuleRequest request);

    void delete(Long id);

    RulePreview preview(String pattern, Long categoryId);
}
