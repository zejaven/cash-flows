package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.ConversionDtos.*;
import org.zeveon.cashflows.service.ConversionRouteService;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/conversion-routes")
@RequiredArgsConstructor
public class ConversionRouteController {

    private final ConversionRouteService routeService;

    @GetMapping
    public ResponseEntity<List<RouteDto>> findAll() {
        return ResponseEntity.ok(routeService.findAll());
    }

    @PostMapping
    public ResponseEntity<RouteDto> create(@RequestBody @Valid RouteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routeService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RouteDto> update(@PathVariable Long id,
                                           @RequestBody @Valid RouteRequest request) {
        return ResponseEntity.ok(routeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        routeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/calculate")
    public ResponseEntity<CalcResult> calculate(@PathVariable Long id,
                                                @RequestBody @Valid CalcRequest request) {
        return ResponseEntity.ok(routeService.calculate(id, request));
    }

    @GetMapping("/compare")
    public ResponseEntity<List<CompareRow>> compare(@RequestParam String from,
                                                    @RequestParam String to,
                                                    @RequestParam BigDecimal amount) {
        return ResponseEntity.ok(routeService.compare(from, to, amount));
    }
}
