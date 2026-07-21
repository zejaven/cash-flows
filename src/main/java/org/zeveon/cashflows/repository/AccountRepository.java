package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.Account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByName(String name);

    long countByBankId(Long bankId);

    /**
     * Computed balances for all accounts in one query (FR-1.2): start balance
     * plus signed own-side transactions plus incoming transfer counter-amounts,
     * each side filtered by the affected account's own start date.
     */
    interface AccountBalance {
        Long getId();

        BigDecimal getBalance();
    }

    @Query(nativeQuery = true, value = """
            SELECT a.id AS id,
                   a.start_balance + COALESCE(o.delta, 0) + COALESCE(i.incoming, 0) AS balance
            FROM cashflow.account a
            LEFT JOIN (SELECT t.account_id AS account_id,
                              SUM(CASE WHEN t.type = 'INCOME' THEN t.amount ELSE -t.amount END) AS delta
                       FROM cashflow.transaction t
                       JOIN cashflow.account oa ON oa.id = t.account_id AND t.txn_date >= oa.start_date
                       GROUP BY t.account_id) o ON o.account_id = a.id
            LEFT JOIN (SELECT t.counter_account_id AS account_id,
                              SUM(t.counter_amount) AS incoming
                       FROM cashflow.transaction t
                       JOIN cashflow.account ia ON ia.id = t.counter_account_id AND t.txn_date >= ia.start_date
                       WHERE t.counter_account_id IS NOT NULL
                       GROUP BY t.counter_account_id) i ON i.account_id = a.id
            """)
    List<AccountBalance> findAllBalances();
}
