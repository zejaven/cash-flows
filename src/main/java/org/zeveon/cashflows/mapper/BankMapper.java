package org.zeveon.cashflows.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.zeveon.cashflows.config.GlobalMapperConfig;
import org.zeveon.cashflows.entity.Bank;
import org.zeveon.cashflows.model.dto.BankDto;

/**
 * @author Stanislav Vafin
 */
@Mapper(config = GlobalMapperConfig.class)
public interface BankMapper {

    BankMapper INSTANCE = Mappers.getMapper(BankMapper.class);

    @Mapping(target = "accountCount", source = "accountCount")
    BankDto toDto(Bank bank, long accountCount);
}
