package org.zeveon.cashflows.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.zeveon.cashflows.entity.enums.DebtEntryRole;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Links a regular EXPENSE/INCOME transaction to a {@link Debt} as a principal
 * movement (FR-13.2). A transaction belongs to at most one debt movement
 * (unique transaction FK). Two amounts because currencies may differ:
 * {@code txnPrincipal} — the part of the transaction amount that is principal,
 * in the account's currency (stats subtract exactly this, the rest — e.g. loan
 * interest — stays a real expense); {@code debtPrincipal} — how much the debt's
 * outstanding changes, in the debt's currency. DB cascade deletes the entry
 * with its transaction, which also "reopens" a referenced schedule row.
 *
 * @author Stanislav Vafin
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "debt_entry", schema = "cashflow")
public class DebtEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Exclude
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debt_id", nullable = false)
    private Debt debt;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private DebtEntryRole role;

    @Column(name = "txn_principal", nullable = false)
    private BigDecimal txnPrincipal;

    @Column(name = "debt_principal", nullable = false)
    private BigDecimal debtPrincipal;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_item_id")
    private LoanScheduleItem scheduleItem;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
