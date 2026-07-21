package org.zeveon.cashflows.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Daily FX table snapshot (FR-6.3): the whole USD-based rate map from
 * open.er-api.com as jsonb, one row per day. Historical conversions use the
 * snapshot with the greatest date not after the transaction date.
 *
 * @author Stanislav Vafin
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "fx_rate_snapshot", schema = "cashflow")
public class FxRateSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Exclude
    @Column(name = "id")
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "base_currency", nullable = false)
    private String baseCurrency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rates", nullable = false)
    private Map<String, Double> rates;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
