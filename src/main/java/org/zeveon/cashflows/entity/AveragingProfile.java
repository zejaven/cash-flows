package org.zeveon.cashflows.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.zeveon.cashflows.entity.enums.AveragingGranularity;
import org.zeveon.cashflows.entity.enums.CategoryFilterMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Average-spend profile (FR-8.x): computes an average from history over a period
 * with a category filter and feeds the forecast as a daily/monthly virtual
 * expense on {@link #account}. Always computed on the fly (FR-8.3);
 * {@link #manualOverride} wins when set (FR-8.4). System categories (Correction)
 * are excluded unconditionally.
 *
 * @author Stanislav Vafin
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "averaging_profile", schema = "cashflow")
public class AveragingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Exclude
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "granularity", nullable = false)
    private AveragingGranularity granularity;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_filter_mode", nullable = false)
    private CategoryFilterMode categoryFilterMode;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "averaging_profile_category", schema = "cashflow",
            joinColumns = @JoinColumn(name = "profile_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    @Builder.Default
    private Set<Category> categories = new LinkedHashSet<>();

    @Column(name = "manual_override")
    private BigDecimal manualOverride;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
