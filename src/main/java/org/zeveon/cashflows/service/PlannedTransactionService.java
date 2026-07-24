package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.PlannedDtos.PlannedConfirmRequest;
import org.zeveon.cashflows.model.dto.PlannedDtos.PlannedDto;
import org.zeveon.cashflows.model.dto.PlannedDtos.PlannedRequest;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface PlannedTransactionService {

    List<PlannedDto> findAll();

    PlannedDto create(PlannedRequest request);

    PlannedDto update(Long id, PlannedRequest request);

    /** Toggle whether the planned one-off contributes to the forecast. */
    PlannedDto setForecastVisible(Long id, boolean visible);

    void delete(Long id);

    PlannedDto confirm(Long id, PlannedConfirmRequest request);

    PlannedDto dismiss(Long id);
}
