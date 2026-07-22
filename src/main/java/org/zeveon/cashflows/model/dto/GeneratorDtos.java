package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.zeveon.cashflows.generator.AgentProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AI parser generator DTOs (FR-4.10).
 *
 * @author Stanislav Vafin
 */
public final class GeneratorDtos {

    private GeneratorDtos() {
    }

    /** Feature availability as seen by the UI; providers carry lazy CLI probe results. */
    public record GeneratorOptions(boolean enabled, List<ProviderOption> providers) {
    }

    public record ProviderOption(AgentProvider id, String label, boolean available,
                                 String version, String error, List<String> models) {
    }

    public record GeneratorSettingsDto(@NotNull AgentProvider provider,
                                       @Size(max = 64) String claudeModel,
                                       @Size(max = 64) String codexModel) {
    }

    public record StartResponse(UUID taskId) {
    }

    /** Snapshot of the (single) generation task; log lines travel over SSE, not here. */
    public record TaskSnapshot(UUID taskId, String status, String parserId, String scriptFile,
                               String error, List<String> warnings,
                               Instant startedAt, Instant finishedAt) {
    }
}
