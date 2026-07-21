package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.RecurringDtos.*;

import java.time.LocalDate;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface RecurringService {

    List<RecurringRuleDto> findAll(LocalDate today);

    RecurringRuleDto create(RecurringRuleRequest request, LocalDate today);

    RecurringRuleDto update(Long id, RecurringRuleRequest request, LocalDate today);

    void delete(Long id);

    /** Due recurring occurrences plus due planned one-offs (FR-7.3). */
    List<QueueItemDto> queue(LocalDate today);

    OccurrenceDto confirm(ConfirmRequest request);

    OccurrenceDto skip(SkipRequest request);

    List<OccurrenceDto> occurrences(Long ruleId);

    void deleteOccurrence(Long occurrenceId);
}
