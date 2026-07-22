package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.model.TransactionFilter;
import org.zeveon.cashflows.model.dto.BulkCategoryRequest;
import org.zeveon.cashflows.model.dto.ConvertToTransferRequest;
import org.zeveon.cashflows.model.dto.DayTotalsResponse;
import org.zeveon.cashflows.model.dto.PageDto;
import org.zeveon.cashflows.model.dto.SummaryDto;
import org.zeveon.cashflows.model.dto.TransactionDto;
import org.zeveon.cashflows.model.dto.TransactionRequest;
import org.zeveon.cashflows.service.AggregationService;
import org.zeveon.cashflows.service.TransactionService;

import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final AggregationService aggregationService;

    @GetMapping
    public ResponseEntity<PageDto<TransactionDto>> find(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean needsReview,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var filter = new TransactionFilter(from, to, accountId, categoryId, type, q, needsReview);
        return ResponseEntity.ok(transactionService.find(filter, page, Math.min(size, 200)));
    }

    @PostMapping
    public ResponseEntity<TransactionDto> create(@RequestBody @Valid TransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionDto> update(@PathVariable Long id,
                                                 @RequestBody @Valid TransactionRequest request) {
        return ResponseEntity.ok(transactionService.update(id, request));
    }

    @PostMapping("/{id}/convert-to-transfer")
    public ResponseEntity<TransactionDto> convertToTransfer(
            @PathVariable Long id, @RequestBody @Valid ConvertToTransferRequest request) {
        return ResponseEntity.ok(transactionService.convertToTransfer(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk-category")
    public ResponseEntity<Void> bulkCategory(@RequestBody @Valid BulkCategoryRequest request) {
        transactionService.bulkCategory(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/day-totals")
    public ResponseEntity<DayTotalsResponse> dayTotals(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean needsReview,
            @RequestParam(defaultValue = "NZD") String currency) {
        var filter = new TransactionFilter(from, to, accountId, categoryId, type, q, needsReview);
        return ResponseEntity.ok(aggregationService.dayTotals(filter, currency));
    }

    @GetMapping("/summary")
    public ResponseEntity<SummaryDto> summary(
            @RequestParam LocalDate date,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean needsReview,
            @RequestParam(defaultValue = "NZD") String currency) {
        var filter = new TransactionFilter(null, null, accountId, categoryId, type, q, needsReview);
        return ResponseEntity.ok(aggregationService.summary(filter, date, currency));
    }
}
