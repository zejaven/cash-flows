package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.PlannedTransaction;
import org.zeveon.cashflows.entity.enums.PlannedStatus;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface PlannedTransactionRepository extends JpaRepository<PlannedTransaction, Long> {

    List<PlannedTransaction> findByStatusOrderByDueDateAsc(PlannedStatus status);

    List<PlannedTransaction> findAllByOrderByDueDateAsc();
}
