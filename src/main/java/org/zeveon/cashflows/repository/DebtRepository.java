package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.Debt;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface DebtRepository extends JpaRepository<Debt, Long> {

    List<Debt> findByClosedFalseOrderByCreatedAtAsc();

    long countByCounterpartyId(Long counterpartyId);
}
