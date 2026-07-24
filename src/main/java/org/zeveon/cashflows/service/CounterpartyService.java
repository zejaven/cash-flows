package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.CounterpartyDto;
import org.zeveon.cashflows.model.dto.CounterpartyRequest;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface CounterpartyService {

    List<CounterpartyDto> findAll(boolean includeArchived);

    CounterpartyDto create(CounterpartyRequest request);

    CounterpartyDto update(Long id, CounterpartyRequest request);

    void delete(Long id);
}
