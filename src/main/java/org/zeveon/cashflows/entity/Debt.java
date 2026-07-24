package org.zeveon.cashflows.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.zeveon.cashflows.entity.enums.DebtDirection;
import org.zeveon.cashflows.entity.enums.DebtKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A single debt: a bank loan or money lent/borrowed personally (FR-13.1). One
 * unified entity for both — a LOAN additionally carries an imported payment
 * schedule ({@link LoanScheduleItem}). Transactions stay plain EXPENSE/INCOME;
 * they attach through {@link DebtEntry} links, so the outstanding amount is
 * {@code openingPrincipal + issues - repayments} in the debt's own currency.
 * {@code openingPrincipal}/{@code anchorDate} mirror the account
 * startBalance/startDate pattern: a debt may predate the app's history
 * (e.g. a mortgage issued years ago) and have no issuing transaction.
 *
 * @author Stanislav Vafin
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "debt", schema = "cashflow")
public class Debt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Exclude
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private DebtDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private DebtKind kind;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "counterparty_id", nullable = false)
    private Counterparty counterparty;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "opening_principal", nullable = false)
    private BigDecimal openingPrincipal;

    @Column(name = "anchor_date", nullable = false)
    private LocalDate anchorDate;

    /**
     * When a PERSONAL debt is expected back — feeds the forecast (FR-13.4).
     */
    @Column(name = "expected_return_date")
    private LocalDate expectedReturnDate;

    /**
     * The account loan payments leave from / the repayment is expected to
     * arrive at. Null = the debt does not participate in the forecast.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    /**
     * Category for the interest part of a LOAN payment — a regular (non-system)
     * EXPENSE category, because interest is real spending and must stay in stats.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interest_category_id")
    private Category interestCategory;

    @Column(name = "note")
    private String note;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
