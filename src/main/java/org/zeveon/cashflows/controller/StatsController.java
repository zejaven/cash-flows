package org.zeveon.cashflows.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zeveon.cashflows.model.dto.StatsDtos.CategoriesResponse;
import org.zeveon.cashflows.model.dto.StatsDtos.FactVsForecast;
import org.zeveon.cashflows.model.dto.StatsDtos.Granularity;
import org.zeveon.cashflows.service.StatsService;

import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/categories")
    public ResponseEntity<CategoriesResponse> categories(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "MONTH") Granularity granularity,
            @RequestParam(defaultValue = "NZD") String currency,
            @RequestParam(defaultValue = "false") boolean includeSystem) {
        return ResponseEntity.ok(statsService.categories(from, to, granularity, currency, includeSystem));
    }

    @GetMapping("/fact-vs-forecast")
    public ResponseEntity<FactVsForecast> factVsForecast(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "NZD") String currency) {
        return ResponseEntity.ok(statsService.factVsForecast(from, to, currency));
    }
}
