package org.zeveon.cashflows.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.zeveon.cashflows.config.GlobalMapperConfig;
import org.zeveon.cashflows.entity.DebtEntry;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.model.dto.TransactionDto;

/**
 * @author Stanislav Vafin
 */
@Mapper(config = GlobalMapperConfig.class)
public interface TransactionMapper {

    TransactionMapper INSTANCE = Mappers.getMapper(TransactionMapper.class);

    default TransactionDto toDto(Transaction transaction) {
        return toDto(transaction, null);
    }

    @Mapping(target = "id", source = "transaction.id")
    @Mapping(target = "date", source = "transaction.txnDate")
    @Mapping(target = "time", source = "transaction.txnTime")
    @Mapping(target = "accountId", source = "transaction.account.id")
    @Mapping(target = "accountName", source = "transaction.account.name")
    @Mapping(target = "accountCurrency", source = "transaction.account.currency")
    @Mapping(target = "categoryId", source = "transaction.category.id")
    @Mapping(target = "categoryName", source = "transaction.category.name")
    @Mapping(target = "categoryColor", source = "transaction.category.color")
    @Mapping(target = "counterAccountId", source = "transaction.counterAccount.id")
    @Mapping(target = "counterAccountName", source = "transaction.counterAccount.name")
    @Mapping(target = "counterAccountCurrency", source = "transaction.counterAccount.currency")
    @Mapping(target = "debtId", source = "entry.debt.id")
    @Mapping(target = "debtName", source = "entry.debt.name")
    @Mapping(target = "debtRole", source = "entry.role")
    @Mapping(target = "txnPrincipal", source = "entry.txnPrincipal")
    TransactionDto toDto(Transaction transaction, DebtEntry entry);
}
