package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.mapper.CategoryMapper;
import org.zeveon.cashflows.model.dto.CategoryDto;
import org.zeveon.cashflows.model.dto.CategoryRequest;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.CategoryService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryDto> findAll(boolean includeArchived) {
        return categoryRepository.findAll().stream()
                .filter(category -> includeArchived || !category.isArchived())
                .map(CategoryMapper.INSTANCE::toDto)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryDto create(CategoryRequest request) {
        var category = categoryRepository.save(Category.builder()
                .name(request.name())
                .type(request.type())
                .color(request.color())
                .archived(Boolean.TRUE.equals(request.archived()))
                .build());
        return CategoryMapper.INSTANCE.toDto(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryDto update(Long id, CategoryRequest request) {
        var category = categoryRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Category", id));
        if (category.isSystem() && category.getType() != request.type()) {
            throw new ConflictException("System category type cannot be changed");
        }
        category.setName(request.name());
        category.setType(request.type());
        category.setColor(request.color());
        if (request.archived() != null) {
            category.setArchived(request.archived());
        }
        return CategoryMapper.INSTANCE.toDto(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var category = categoryRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Category", id));
        if (category.isSystem()) {
            throw new ConflictException("System category cannot be deleted");
        }
        if (transactionRepository.existsByCategoryId(id)) {
            throw new ConflictException("Category has transactions — archive it instead");
        }
        categoryRepository.delete(category);
    }
}
