package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.CategoryDto;
import org.zeveon.cashflows.model.dto.CategoryRequest;
import org.zeveon.cashflows.service.CategoryService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ResponseEntity<List<CategoryDto>> findAll(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(categoryService.findAll(includeArchived));
    }

    @PostMapping
    public ResponseEntity<CategoryDto> create(@RequestBody @Valid CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryDto> update(@PathVariable Long id,
                                              @RequestBody @Valid CategoryRequest request) {
        return ResponseEntity.ok(categoryService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
