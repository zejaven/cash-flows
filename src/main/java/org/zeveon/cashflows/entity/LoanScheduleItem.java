package org.zeveon.cashflows.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of a bank loan's payment schedule as issued by the bank (FR-13.5):
 * exact due date (business-day-shifted), principal/interest split and the
 * remaining balance after the payment. Deliberately not a mapped collection on
 * {@link Debt} — schedules run to hundreds of rows. A row is "paid" when a
 * {@link DebtEntry} references it; there is no status column to fall out of sync.
 *
 * @author Stanislav Vafin
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "loan_schedule_item", schema = "cashflow")
public class LoanScheduleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Exclude
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debt_id", nullable = false)
    private Debt debt;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "principal_amount", nullable = false)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", nullable = false)
    private BigDecimal interestAmount;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "remaining_after")
    private BigDecimal remainingAfter;

    /**
     * Manually excluded from the queue and forecast (payment holiday etc.).
     */
    @Column(name = "skipped", nullable = false)
    private boolean skipped;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
