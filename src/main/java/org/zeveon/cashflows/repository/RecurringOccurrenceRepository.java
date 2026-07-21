package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.RecurringOccurrence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface RecurringOccurrenceRepository extends JpaRepository<RecurringOccurrence, Long> {

    List<RecurringOccurrence> findByRuleId(Long ruleId);

    List<RecurringOccurrence> findByRuleIdIn(List<Long> ruleIds);

    Optional<RecurringOccurrence> findByRuleIdAndDueDate(Long ruleId, LocalDate dueDate);

    boolean existsByRuleId(Long ruleId);
}
