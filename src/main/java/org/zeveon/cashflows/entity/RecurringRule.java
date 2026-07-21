package org.zeveon.cashflows.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.zeveon.cashflows.entity.enums.AmountStrategy;
import org.zeveon.cashflows.entity.enums.ScheduleFrequency;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Recurring operation (FR-7.1/7.2). Due occurrences never create transactions
 * silently — they enter a confirm queue (FR-7.3) and count toward the forecast
 * until acted on. AVERAGE amounts are computed from the last {@code avgLookback}
 * actual transactions of the rule's own category; TRANSFER rules are FIXED-only
 * (transfers carry no category to average over).
 *
 * @author Stanislav Vafin
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "recurring_rule", schema = "cashflow")
public class RecurringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Exclude
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private TransactionType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counter_account_id")
    private Account counterAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_freq", nullable = false)
    private ScheduleFrequency scheduleFreq;

    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    /** ISO: 1 = Monday … 7 = Sunday. */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "interval_days")
    private Integer intervalDays;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_strategy", nullable = false)
    private AmountStrategy amountStrategy;

    @Column(name = "amount")
    private BigDecimal amount;

    /** TRANSFER only; null means "estimate by current FX at confirm/forecast time". */
    @Column(name = "counter_amount")
    private BigDecimal counterAmount;

    @Column(name = "avg_lookback")
    private Integer avgLookback;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
