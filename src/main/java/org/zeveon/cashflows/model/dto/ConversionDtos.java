package org.zeveon.cashflows.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
public final class ConversionDtos {

    private ConversionDtos() {
    }

    public enum Direction {FORWARD, REVERSE}

    public record StepDto(Long id, int position, String fromCurrency, String toCurrency,
                          BigDecimal rate, boolean rateInverted,
                          BigDecimal feeFixed, BigDecimal feePct) {
    }

    public record RouteDto(Long id, String name, boolean archived, List<StepDto> steps,
                           String fromCurrency, String toCurrency) {
    }

    public record StepRequest(
            @NotBlank @Size(max = 8) String fromCurrency,
            @NotBlank @Size(max = 8) String toCurrency,
            @NotNull @Positive BigDecimal rate,
            boolean rateInverted,
            BigDecimal feeFixed,
            BigDecimal feePct) {
    }

    public record RouteRequest(
            @NotBlank @Size(max = 255) String name,
            @NotEmpty @Valid List<StepRequest> steps,
            Boolean archived) {
    }

    public record CalcRequest(@NotNull Direction direction, @NotNull @Positive BigDecimal amount) {
    }

    public record CalcStepDto(int position, String fromCurrency, String toCurrency,
                              BigDecimal amountIn, BigDecimal amountOut,
                              BigDecimal rate, boolean rateInverted,
                              BigDecimal feeFixed, BigDecimal feePct) {
    }

    /**
     * {@code effectiveRate}/{@code marketRate} — FROM units per 1 TO;
     * negative {@code lossPct} = worse than market. Market fields are null when
     * the rate source is unavailable (fail-soft, FR-6.1).
     */
    public record CalcResult(List<CalcStepDto> steps, BigDecimal amountIn, BigDecimal amountOut,
                             BigDecimal effectiveRate, BigDecimal marketRate, BigDecimal lossPct) {
    }

    public record CompareRow(Long routeId, String name, BigDecimal effectiveRate,
                             BigDecimal amountOut, BigDecimal lossPct, boolean best) {
    }
}
