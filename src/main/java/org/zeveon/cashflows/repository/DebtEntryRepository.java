package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.DebtEntry;
import org.zeveon.cashflows.entity.enums.DebtEntryRole;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface DebtEntryRepository extends JpaRepository<DebtEntry, Long> {

    Optional<DebtEntry> findByTransactionId(Long transactionId);

    Optional<DebtEntry> findByScheduleItemId(Long scheduleItemId);

    List<DebtEntry> findByTransactionIdIn(Collection<Long> transactionIds);

    List<DebtEntry> findByDebtIdOrderByCreatedAtDesc(Long debtId);

    boolean existsByDebtId(Long debtId);

    /**
     * Principal totals per debt and role in one query — feeds outstanding
     * computation for lists and the dashboard summary (FR-13.3).
     */
    interface PrincipalSum {
        Long getDebtId();

        DebtEntryRole getRole();

        BigDecimal getTotal();
    }

    @Query("""
            SELECT e.debt.id AS debtId, e.role AS role, SUM(e.debtPrincipal) AS total
            FROM DebtEntry e GROUP BY e.debt.id, e.role
            """)
    List<PrincipalSum> sumPrincipalsByDebt();
}
