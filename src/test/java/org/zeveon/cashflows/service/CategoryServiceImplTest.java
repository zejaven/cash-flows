package org.zeveon.cashflows.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.model.dto.CategoryRequest;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.impl.CategoryServiceImpl;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * @author Stanislav Vafin
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    private Category systemCategory() {
        return Category.builder()
                .id(1L)
                .name("Correction")
                .type(CategoryType.EXPENSE)
                .color("#8a96ab")
                .system(true)
                .archived(false)
                .build();
    }

    @Test
    @DisplayName("system category cannot be deleted")
    void systemDeleteForbidden() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(systemCategory()));
        assertThatThrownBy(() -> categoryService.delete(1L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("system category cannot be renamed, retyped or archived")
    void systemUpdateGuards() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(systemCategory()));
        assertThatThrownBy(() -> categoryService.update(1L,
                new CategoryRequest("Renamed", CategoryType.EXPENSE, "#8a96ab", null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> categoryService.update(1L,
                new CategoryRequest("Correction", CategoryType.INCOME, "#8a96ab", null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> categoryService.update(1L,
                new CategoryRequest("Correction", CategoryType.EXPENSE, "#8a96ab", true)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("system category color is editable")
    void systemColorEditable() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(systemCategory()));
        assertThatCode(() -> categoryService.update(1L,
                new CategoryRequest("Correction", CategoryType.EXPENSE, "#123456", null)))
                .doesNotThrowAnyException();
    }
}
