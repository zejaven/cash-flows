package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.BankDto;
import org.zeveon.cashflows.model.dto.BankRequest;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface BankService {

    List<BankDto> findAll();

    BankDto create(BankRequest request);

    BankDto update(Long id, BankRequest request);

    void delete(Long id);
}
