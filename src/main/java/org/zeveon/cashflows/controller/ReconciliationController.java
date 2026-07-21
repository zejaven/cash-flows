package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.ReconciliationDtos.CorrectionDto;
import org.zeveon.cashflows.model.dto.ReconciliationDtos.HistoryEntry;
import org.zeveon.cashflows.model.dto.ReconciliationDtos.ReconciliationRequest;
import org.zeveon.cashflows.model.dto.ReconciliationDtos.ReconciliationRow;
import org.zeveon.cashflows.service.ReconciliationService;

import java.time.LocalDate;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @GetMapping
    public ResponseEntity<List<ReconciliationRow>> rows(@RequestParam LocalDate date) {
        return ResponseEntity.ok(reconciliationService.rows(date));
    }

    @PostMapping
    public ResponseEntity<List<CorrectionDto>> reconcile(
            @RequestBody @Valid ReconciliationRequest request) {
        return ResponseEntity.ok(reconciliationService.reconcile(request));
    }

    @GetMapping("/history")
    public ResponseEntity<List<HistoryEntry>> history() {
        return ResponseEntity.ok(reconciliationService.history());
    }
}
