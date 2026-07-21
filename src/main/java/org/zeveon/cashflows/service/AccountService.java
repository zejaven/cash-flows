package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.AccountDto;
import org.zeveon.cashflows.model.dto.AccountRequest;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface AccountService {

    List<AccountDto> findAll(boolean includeArchived);

    AccountDto create(AccountRequest request);

    AccountDto update(Long id, AccountRequest request);

    void delete(Long id);
}
