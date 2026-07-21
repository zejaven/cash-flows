package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.util.List;
import java.util.Set;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>,
        JpaSpecificationExecutor<Transaction> {

    boolean existsByAccountIdOrCounterAccountId(Long accountId, Long counterAccountId);

    boolean existsByCategoryId(Long categoryId);

    /**
     * All statement-row dedup keys already present (both transfer sides), FR-4.5.
     */
    @Query("""
            SELECT t.importKey FROM Transaction t WHERE t.importKey IS NOT NULL
            UNION
            SELECT t.counterImportKey FROM Transaction t WHERE t.counterImportKey IS NOT NULL
            """)
    Set<String> findAllImportKeys();

    List<Transaction> findByCategoryIsNullAndTypeNot(TransactionType type);

    List<Transaction> findByCategorySystemTrueOrderByTxnDateDesc();
}
