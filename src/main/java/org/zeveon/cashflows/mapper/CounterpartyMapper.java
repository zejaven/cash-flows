package org.zeveon.cashflows.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.zeveon.cashflows.config.GlobalMapperConfig;
import org.zeveon.cashflows.entity.Counterparty;
import org.zeveon.cashflows.model.dto.CounterpartyDto;

/**
 * @author Stanislav Vafin
 */
@Mapper(config = GlobalMapperConfig.class)
public interface CounterpartyMapper {

    CounterpartyMapper INSTANCE = Mappers.getMapper(CounterpartyMapper.class);

    @Mapping(target = "debtCount", source = "debtCount")
    CounterpartyDto toDto(Counterparty counterparty, long debtCount);
}
