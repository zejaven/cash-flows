package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.RecurringRule;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface RecurringRuleRepository extends JpaRepository<RecurringRule, Long> {

    List<RecurringRule> findByArchivedFalseOrderByIdAsc();
}
