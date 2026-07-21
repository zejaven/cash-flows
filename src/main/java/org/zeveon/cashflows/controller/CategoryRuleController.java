package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.CategoryRuleDtos.CategoryRuleDto;
import org.zeveon.cashflows.model.dto.CategoryRuleDtos.CategoryRuleRequest;
import org.zeveon.cashflows.model.dto.CategoryRuleDtos.CategoryRuleSaveResult;
import org.zeveon.cashflows.model.dto.CategoryRuleDtos.RulePreview;
import org.zeveon.cashflows.service.CategoryRuleService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/category-rules")
@RequiredArgsConstructor
public class CategoryRuleController {

    private final CategoryRuleService categoryRuleService;

    @GetMapping
    public ResponseEntity<List<CategoryRuleDto>> findAll() {
        return ResponseEntity.ok(categoryRuleService.findAll());
    }

    @PostMapping
    public ResponseEntity<CategoryRuleSaveResult> create(@RequestBody @Valid CategoryRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryRuleService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryRuleSaveResult> update(@PathVariable Long id,
                                                         @RequestBody @Valid CategoryRuleRequest request) {
        return ResponseEntity.ok(categoryRuleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryRuleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preview")
    public ResponseEntity<RulePreview> preview(@RequestParam String pattern,
                                               @RequestParam Long categoryId) {
        return ResponseEntity.ok(categoryRuleService.preview(pattern, categoryId));
    }
}
