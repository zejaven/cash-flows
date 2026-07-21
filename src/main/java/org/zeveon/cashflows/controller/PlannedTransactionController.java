package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.PlannedDtos.PlannedConfirmRequest;
import org.zeveon.cashflows.model.dto.PlannedDtos.PlannedDto;
import org.zeveon.cashflows.model.dto.PlannedDtos.PlannedRequest;
import org.zeveon.cashflows.service.PlannedTransactionService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/planned")
@RequiredArgsConstructor
public class PlannedTransactionController {

    private final PlannedTransactionService plannedService;

    @GetMapping
    public ResponseEntity<List<PlannedDto>> findAll() {
        return ResponseEntity.ok(plannedService.findAll());
    }

    @PostMapping
    public ResponseEntity<PlannedDto> create(@RequestBody @Valid PlannedRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(plannedService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlannedDto> update(@PathVariable Long id,
                                             @RequestBody @Valid PlannedRequest request) {
        return ResponseEntity.ok(plannedService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        plannedService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<PlannedDto> confirm(@PathVariable Long id,
                                              @RequestBody @Valid PlannedConfirmRequest request) {
        return ResponseEntity.ok(plannedService.confirm(id, request));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<PlannedDto> dismiss(@PathVariable Long id) {
        return ResponseEntity.ok(plannedService.dismiss(id));
    }
}
