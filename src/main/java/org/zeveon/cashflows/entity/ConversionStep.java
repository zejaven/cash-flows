package org.zeveon.cashflows.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One step of a conversion route. The rate is stored exactly as the user typed
 * it plus a direction flag — an inverted quote like "478.20 KZT per 1 USDT"
 * would lose ~0.3% if forced into a multiplier within numeric(19,6):
 * {@code rateInverted=false} → rate = TO units per 1 FROM;
 * {@code rateInverted=true}  → rate = FROM units per 1 TO.
 * {@code feeFixed} is in the step's TO currency; {@code feePct} is the provider's
 * commission as a percentage of the delivered (TO) amount.
 *
 * @author Stanislav Vafin
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "conversion_step", schema = "cashflow")
public class ConversionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Exclude
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    @EqualsAndHashCode.Exclude
    private ConversionRoute route;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "from_currency", nullable = false)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false)
    private String toCurrency;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    @Column(name = "rate_inverted", nullable = false)
    private boolean rateInverted;

    @Column(name = "fee_fixed", nullable = false)
    private BigDecimal feeFixed;

    @Column(name = "fee_pct", nullable = false)
    private BigDecimal feePct;
}
