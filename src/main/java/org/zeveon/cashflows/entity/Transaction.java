package org.zeveon.cashflows.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A money movement on an account. Amounts are always positive; the sign is implied
 * by {@link #type}. A TRANSFER is a single row: {@code amount} leaves {@code account},
 * {@code counterAmount} (in the counter account's currency) arrives at
 * {@code counterAccount} — the effective rate is derived, not stored.
 * {@code category == null} on EXPENSE/INCOME means "needs review" (FR-4.7).
 *
 * @author Stanislav Vafin
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "transaction", schema = "cashflow")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Exclude
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private TransactionType type;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "description")
    private String description;

    /**
     * When the operation was charged in a currency other than the account's
     * (e.g. an NZD purchase from a USD account), the original pair is kept (FR-2.1).
     */
    @Column(name = "original_currency")
    private String originalCurrency;

    @Column(name = "original_amount")
    private BigDecimal originalAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counter_account_id")
    private Account counterAccount;

    @Column(name = "counter_amount")
    private BigDecimal counterAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private TransactionSource source;

    @Column(name = "txn_time")
    private LocalTime txnTime;

    @Column(name = "reference")
    private String reference;

    @Column(name = "auth_code")
    private String authCode;

    /**
     * Immutable dedup key of the source statement row (FR-4.5): survives user
     * edits so re-imports of overlapping periods stay idempotent. For a TRANSFER,
     * {@link #counterImportKey} holds the receiving-side row's key.
     */
    @Column(name = "import_key")
    private String importKey;

    @Column(name = "counter_import_key")
    private String counterImportKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_run_id")
    private ImportRun importRun;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
