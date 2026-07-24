package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.conversion.RouteCalculator;
import org.zeveon.cashflows.entity.ConversionRoute;
import org.zeveon.cashflows.entity.ConversionStep;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.model.dto.ConversionDtos.*;
import org.zeveon.cashflows.repository.ConversionRouteRepository;
import org.zeveon.cashflows.service.ConversionRouteService;
import org.zeveon.cashflows.service.FxService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class ConversionRouteServiceImpl implements ConversionRouteService {

    private final ConversionRouteRepository routeRepository;
    private final FxService fxService;

    @Override
    @Transactional(readOnly = true)
    public List<RouteDto> findAll() {
        return routeRepository.findAll().stream()
                .sorted(Comparator.comparing(ConversionRoute::getId))
                .map(ConversionRouteServiceImpl::toDto)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RouteDto create(RouteRequest request) {
        var route = new ConversionRoute();
        apply(route, request);
        return toDto(routeRepository.save(route));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RouteDto update(Long id, RouteRequest request) {
        var route = routeRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Conversion route", id));
        apply(route, request);
        return toDto(route);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        routeRepository.delete(routeRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Conversion route", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public CalcResult calculate(Long id, CalcRequest request) {
        var route = routeRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Conversion route", id));
        var result = request.direction() == Direction.FORWARD
                ? RouteCalculator.forward(route.getSteps(), request.amount())
                : RouteCalculator.reverse(route.getSteps(), request.amount());
        return withMarket(route, result);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CompareRow> compare(String from, String to, BigDecimal amount) {
        var candidates = routeRepository.findByArchivedFalseOrderByIdAsc().stream()
                .filter(route -> !route.getSteps().isEmpty())
                .filter(route -> route.getSteps().getFirst().getFromCurrency().equalsIgnoreCase(from)
                        && route.getSteps().getLast().getToCurrency().equalsIgnoreCase(to))
                .toList();
        var rows = candidates.stream()
                .map(route -> {
                    try {
                        var calc = withMarket(route, RouteCalculator.forward(route.getSteps(), amount));
                        return new CompareRow(route.getId(), route.getName(), calc.effectiveRate(),
                                calc.amountOut(), calc.lossPct(), false);
                    } catch (IllegalArgumentException e) {
                        return null;  // amount too small for this route's fees
                    }
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CompareRow::effectiveRate))
                .toList();
        // best = lowest effective rate (fewest FROM units per 1 TO)
        return rows.isEmpty() ? rows
                : rows.stream()
                .map(row -> new CompareRow(row.routeId(), row.name(), row.effectiveRate(),
                        row.amountOut(), row.lossPct(),
                        row.effectiveRate().compareTo(rows.getFirst().effectiveRate()) == 0))
                .toList();
    }

    // --- helpers ---

    private CalcResult withMarket(ConversionRoute route, RouteCalculator.Result result) {
        var from = route.getSteps().getFirst().getFromCurrency();
        var to = route.getSteps().getLast().getToCurrency();
        BigDecimal marketRate = null;
        BigDecimal lossPct = null;
        var rates = fxService.currentRates();
        if (rates.isPresent() && result.effectiveRate() != null) {
            // market: FROM units per 1 TO
            var market = fxService.convert(BigDecimal.ONE, to, from, rates.get().rates());
            if (market.isPresent()) {
                marketRate = market.get().setScale(6, RoundingMode.HALF_UP);
                lossPct = marketRate.subtract(result.effectiveRate())
                        .divide(marketRate, java.math.MathContext.DECIMAL64)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }
        var steps = new java.util.ArrayList<CalcStepDto>();
        for (var index = 0; index < result.steps().size(); index++) {
            var stepResult = result.steps().get(index);
            var step = route.getSteps().get(index);
            steps.add(new CalcStepDto(stepResult.position(), stepResult.fromCurrency(),
                    stepResult.toCurrency(), stepResult.amountIn(), stepResult.amountOut(),
                    step.getRate(), step.isRateInverted(), step.getFeeFixed(), step.getFeePct()));
        }
        return new CalcResult(steps, result.amountIn(), result.amountOut(),
                result.effectiveRate(), marketRate, lossPct);
    }

    private static void apply(ConversionRoute route, RouteRequest request) {
        route.setName(request.name());
        if (request.archived() != null) {
            route.setArchived(request.archived());
        }
        // Reuse existing step rows in place rather than clear-and-re-add: the
        // conversion_step (route_id, position) unique constraint would otherwise
        // be transiently violated, because Hibernate flushes the inserts of the
        // new positions 1..n before the orphan-removal deletes of the old ones.
        var steps = route.getSteps();
        var requests = request.steps();
        for (var index = 0; index < requests.size(); index++) {
            var stepRequest = requests.get(index);
            var step = index < steps.size() ? steps.get(index) : newStep(route, steps);
            step.setPosition(index + 1);
            step.setFromCurrency(stepRequest.fromCurrency().toUpperCase());
            step.setToCurrency(stepRequest.toCurrency().toUpperCase());
            step.setRate(stepRequest.rate());
            step.setRateInverted(stepRequest.rateInverted());
            step.setFeeFixed(stepRequest.feeFixed() == null ? BigDecimal.ZERO : stepRequest.feeFixed());
            step.setFeePct(stepRequest.feePct() == null ? BigDecimal.ZERO : stepRequest.feePct());
        }
        // Drop any trailing steps the request no longer contains (highest
        // positions first, so the deletes never collide with a kept row).
        while (steps.size() > requests.size()) {
            steps.remove(steps.size() - 1);
        }
    }

    private static ConversionStep newStep(ConversionRoute route, List<ConversionStep> steps) {
        var step = new ConversionStep();
        step.setRoute(route);
        steps.add(step);
        return step;
    }

    private static RouteDto toDto(ConversionRoute route) {
        var steps = route.getSteps().stream()
                .map(step -> new StepDto(step.getId(), step.getPosition(), step.getFromCurrency(),
                        step.getToCurrency(), step.getRate(), step.isRateInverted(),
                        step.getFeeFixed(), step.getFeePct()))
                .toList();
        return new RouteDto(route.getId(), route.getName(), route.isArchived(), steps,
                steps.isEmpty() ? null : steps.getFirst().fromCurrency(),
                steps.isEmpty() ? null : steps.getLast().toCurrency());
    }
}
