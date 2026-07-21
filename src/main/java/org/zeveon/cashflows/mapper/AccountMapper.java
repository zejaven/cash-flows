package org.zeveon.cashflows.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.zeveon.cashflows.config.GlobalMapperConfig;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.model.dto.AccountDto;

import java.math.BigDecimal;

/**
 * @author Stanislav Vafin
 */
@Mapper(config = GlobalMapperConfig.class)
public interface AccountMapper {

    AccountMapper INSTANCE = Mappers.getMapper(AccountMapper.class);

    @Mapping(target = "bankId", source = "account.bank.id")
    @Mapping(target = "bankName", source = "account.bank.name")
    @Mapping(target = "balance", source = "balance")
    AccountDto toDto(Account account, BigDecimal balance);
}
