package org.zeveon.cashflows.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /**
     * Latest actual payments of a category — feed of the AVERAGE amount strategy (FR-7.2).
     */
    List<Transaction> findByTypeAndCategoryIdOrderByTxnDateDescIdDesc(
            TransactionType type, Long categoryId, Pageable pageable);

    /**
     * Signed per-day balance deltas per account since a date (both transaction
     * sides, each respecting its account's start_date) — the history series of
     * the forecast chart, computed in one query.
     */
    interface DailyDelta {
        Long getAccountId();

        LocalDate getDay();

        BigDecimal getDelta();
    }

    @Query(nativeQuery = true, value = """
            SELECT account_id AS accountId, day, SUM(delta) AS delta FROM (
                SELECT t.account_id,
                       t.txn_date AS day,
                       CASE WHEN t.type = 'INCOME' THEN t.amount ELSE -t.amount END AS delta
                FROM cashflow.transaction t
                JOIN cashflow.account oa ON oa.id = t.account_id AND t.txn_date >= oa.start_date
                WHERE t.txn_date >= :from AND t.txn_date <= :to
                UNION ALL
                SELECT t.counter_account_id,
                       t.txn_date,
                       t.counter_amount
                FROM cashflow.transaction t
                JOIN cashflow.account ia ON ia.id = t.counter_account_id AND t.txn_date >= ia.start_date
                WHERE t.counter_account_id IS NOT NULL AND t.txn_date >= :from AND t.txn_date <= :to
            ) deltas
            GROUP BY account_id, day
            """)
    List<DailyDelta> findDailyDeltas(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
