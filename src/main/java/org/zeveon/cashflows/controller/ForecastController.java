package org.zeveon.cashflows.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zeveon.cashflows.model.dto.ForecastDtos.ForecastResponse;
import org.zeveon.cashflows.service.ForecastService;

import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/forecast")
@RequiredArgsConstructor
public class ForecastController {

    private final ForecastService forecastService;

    @GetMapping
    public ResponseEntity<ForecastResponse> forecast(
            @RequestParam LocalDate today,
            @RequestParam(defaultValue = "12") int horizonMonths,
            @RequestParam(defaultValue = "NZD") String currency,
            @RequestParam(required = false) LocalDate historyFrom) {
        var from = historyFrom != null ? historyFrom : today.minusMonths(6);
        return ResponseEntity.ok(forecastService.forecast(today,
                Math.min(Math.max(horizonMonths, 1), 36), currency.toUpperCase(), from));
    }
}
