package org.zeveon.cashflows.service;

import org.springframework.web.multipart.MultipartFile;
import org.zeveon.cashflows.model.dto.DebtDtos.*;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface DebtService {

    List<DebtDto> findAll(boolean includeClosed);

    DebtDto create(DebtRequest request);

    DebtDto update(Long id, DebtRequest request);

    void delete(Long id);

    DebtDto setClosed(Long id, boolean closed);

    DebtSummaryDto summary(String currency);

    List<DebtEntryDto> entries(Long debtId);

    DebtEntryDto link(Long debtId, DebtLinkRequest request);

    void unlink(Long entryId);

    DebtEntryDto record(Long debtId, DebtRecordRequest request);

    List<LoanScheduleItemDto> schedule(Long debtId);

    ScheduleImportResult importSchedule(Long debtId, String parserId, MultipartFile file);

    DebtEntryDto confirmPayment(LoanConfirmRequest request);

    void setScheduleItemSkipped(Long scheduleItemId, boolean skipped);
}
