package org.zeveon.cashflows.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.LoanScheduleItem;

import java.time.LocalDate;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface LoanScheduleItemRepository extends JpaRepository<LoanScheduleItem, Long> {

    List<LoanScheduleItem> findByDebtIdOrderByDueDateAscPositionAsc(Long debtId);

    /**
     * Unpaid (no {@link org.zeveon.cashflows.entity.DebtEntry} references the row),
     * unskipped rows of open debts due on or before the date — the confirm queue
     * (to = today) and the forecast (to = horizon end) share this query. Rows due
     * on or before the debt's anchor date are already inside its opening
     * principal — imported history, never payable.
     */
    @Query("""
            SELECT i FROM LoanScheduleItem i
            JOIN FETCH i.debt d
            LEFT JOIN FETCH d.account
            WHERE d.closed = FALSE AND i.skipped = FALSE
              AND i.dueDate > d.anchorDate
              AND i.dueDate <= :to
              AND NOT EXISTS (SELECT 1 FROM DebtEntry e WHERE e.scheduleItem = i)
            ORDER BY i.dueDate ASC, i.position ASC
            """)
    List<LoanScheduleItem> findPendingUpTo(@Param("to") LocalDate to);

    /**
     * The debt's earliest unpaid, unskipped row after the anchor — "next
     * payment" on the list card.
     */
    @Query("""
            SELECT i FROM LoanScheduleItem i
            WHERE i.debt.id = :debtId AND i.skipped = FALSE
              AND i.dueDate > i.debt.anchorDate
              AND NOT EXISTS (SELECT 1 FROM DebtEntry e WHERE e.scheduleItem = i)
            ORDER BY i.dueDate ASC, i.position ASC
            """)
    List<LoanScheduleItem> findPendingByDebt(@Param("debtId") Long debtId, Pageable pageable);

    /**
     * Re-import replace window: future-or-overlapping rows that were neither
     * paid nor manually skipped make way for the reissued schedule (FR-13.6).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM LoanScheduleItem i
            WHERE i.debt.id = :debtId AND i.dueDate >= :from AND i.skipped = FALSE
              AND NOT EXISTS (SELECT 1 FROM DebtEntry e WHERE e.scheduleItem = i)
            """)
    int deleteReplaceable(@Param("debtId") Long debtId, @Param("from") LocalDate from);
}
