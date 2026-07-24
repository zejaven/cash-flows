package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.zeveon.cashflows.entity.enums.AveragingGranularity;
import org.zeveon.cashflows.entity.enums.AveragingWindowMode;
import org.zeveon.cashflows.entity.enums.CategoryFilterMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
public final class AveragingProfileDtos {

    private AveragingProfileDtos() {
    }

    /** Computed result shown in the editor (mockup 4a) and fed to the forecast. */
    public record ComputedValue(BigDecimal value, long dayCount, long txnCount,
                                BigDecimal total, boolean approx) {
    }

    public record ProfileDto(
            Long id, String name,
            Long accountId, String accountName, String accountCurrency,
            AveragingGranularity granularity,
            AveragingWindowMode windowMode, LocalDate periodFrom, LocalDate periodTo, Integer rollingDays,
            CategoryFilterMode categoryFilterMode, List<Long> categoryIds,
            BigDecimal manualOverride, boolean archived, boolean forecastVisible,
            ComputedValue computed, BigDecimal effectiveValue) {
    }

    public record ProfileRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull Long accountId,
            @NotNull AveragingGranularity granularity,
            @NotNull AveragingWindowMode windowMode,
            LocalDate periodFrom,
            LocalDate periodTo,
            Integer rollingDays,
            @NotNull CategoryFilterMode categoryFilterMode,
            List<Long> categoryIds,
            BigDecimal manualOverride,
            Boolean archived) {
    }

    public record PreviewRequest(
            @NotNull Long accountId,
            @NotNull AveragingGranularity granularity,
            @NotNull AveragingWindowMode windowMode,
            LocalDate periodFrom,
            LocalDate periodTo,
            Integer rollingDays,
            @NotNull CategoryFilterMode categoryFilterMode,
            List<Long> categoryIds) {
    }
}
