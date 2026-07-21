package org.zeveon.cashflows.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.zeveon.cashflows.config.GlobalMapperConfig;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.model.dto.CategoryDto;

/**
 * @author Stanislav Vafin
 */
@Mapper(config = GlobalMapperConfig.class)
public interface CategoryMapper {

    CategoryMapper INSTANCE = Mappers.getMapper(CategoryMapper.class);

    CategoryDto toDto(Category category);
}
