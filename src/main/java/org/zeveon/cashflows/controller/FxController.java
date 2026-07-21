package org.zeveon.cashflows.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zeveon.cashflows.model.dto.FxRatesDto;
import org.zeveon.cashflows.service.FxService;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/fx")
@RequiredArgsConstructor
public class FxController {

    private final FxService fxService;

    /**
     * 204 when the rate source is unavailable — the frontend falls back to
     * showing original currencies (fail-soft, FR-6.1).
     */
    @GetMapping("/rates")
    public ResponseEntity<FxRatesDto> rates() {
        return fxService.currentRates()
                .map(rates -> ResponseEntity.ok(new FxRatesDto(rates.base(), rates.rates(), rates.updatedAt())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
