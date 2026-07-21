package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.ReconciliationDtos.CorrectionDto;
import org.zeveon.cashflows.model.dto.ReconciliationDtos.HistoryEntry;
import org.zeveon.cashflows.model.dto.ReconciliationDtos.ReconciliationRequest;
import org.zeveon.cashflows.model.dto.ReconciliationDtos.ReconciliationRow;

import java.time.LocalDate;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface ReconciliationService {

    List<ReconciliationRow> rows(LocalDate date);

    List<CorrectionDto> reconcile(ReconciliationRequest request);

    List<HistoryEntry> history();
}
