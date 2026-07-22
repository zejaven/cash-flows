package org.zeveon.cashflows.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;
import org.zeveon.cashflows.generator.AgentProvider;

import java.time.Instant;

/**
 * Single-row (id = 1) app-wide selection of the AI provider and model used by
 * the parser generator (FR-4.10); seeded by Liquibase, edited from Settings.
 *
 * @author Stanislav Vafin
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "generator_settings", schema = "cashflow")
public class GeneratorSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private AgentProvider provider;

    /** Blank/null = the CLI's own default model. */
    @Column(name = "claude_model")
    private String claudeModel;

    @Column(name = "codex_model")
    private String codexModel;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
