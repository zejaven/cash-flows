package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.Transaction;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>,
        JpaSpecificationExecutor<Transaction> {

    boolean existsByAccountIdOrCounterAccountId(Long accountId, Long counterAccountId);

    boolean existsByCategoryId(Long categoryId);
}
