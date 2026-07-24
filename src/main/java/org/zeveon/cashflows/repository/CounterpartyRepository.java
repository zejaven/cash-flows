package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.Counterparty;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface CounterpartyRepository extends JpaRepository<Counterparty, Long> {
}
