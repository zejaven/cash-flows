package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.CategoryDto;
import org.zeveon.cashflows.model.dto.CategoryRequest;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface CategoryService {

    List<CategoryDto> findAll(boolean includeArchived);

    CategoryDto create(CategoryRequest request);

    CategoryDto update(Long id, CategoryRequest request);

    void delete(Long id);
}
