package org.zeveon.cashflows.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.zeveon.cashflows.config.GlobalMapperConfig;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.model.dto.TransactionDto;

/**
 * @author Stanislav Vafin
 */
@Mapper(config = GlobalMapperConfig.class)
public interface TransactionMapper {

    TransactionMapper INSTANCE = Mappers.getMapper(TransactionMapper.class);

    @Mapping(target = "date", source = "txnDate")
    @Mapping(target = "time", source = "txnTime")
    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "accountName", source = "account.name")
    @Mapping(target = "accountCurrency", source = "account.currency")
    @Mapping(target = "categoryId", source = "category.id")
    @Mapping(target = "categoryName", source = "category.name")
    @Mapping(target = "categoryColor", source = "category.color")
    @Mapping(target = "counterAccountId", source = "counterAccount.id")
    @Mapping(target = "counterAccountName", source = "counterAccount.name")
    @Mapping(target = "counterAccountCurrency", source = "counterAccount.currency")
    TransactionDto toDto(Transaction transaction);
}
