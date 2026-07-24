package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.zeveon.cashflows.model.dto.DebtDtos.*;
import org.zeveon.cashflows.service.DebtService;
import org.zeveon.cashflows.statement.LoanScheduleParserService;
import org.zeveon.cashflows.statement.ParserInfo;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/debts")
@RequiredArgsConstructor
public class DebtController {

    private final DebtService debtService;
    private final LoanScheduleParserService scheduleParserService;

    @GetMapping
    public ResponseEntity<List<DebtDto>> findAll(
            @RequestParam(defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(debtService.findAll(includeClosed));
    }

    @PostMapping
    public ResponseEntity<DebtDto> create(@RequestBody @Valid DebtRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(debtService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DebtDto> update(@PathVariable Long id,
                                          @RequestBody @Valid DebtRequest request) {
        return ResponseEntity.ok(debtService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        debtService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<DebtDto> close(@PathVariable Long id) {
        return ResponseEntity.ok(debtService.setClosed(id, true));
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<DebtDto> reopen(@PathVariable Long id) {
        return ResponseEntity.ok(debtService.setClosed(id, false));
    }

    @GetMapping("/summary")
    public ResponseEntity<DebtSummaryDto> summary(@RequestParam(defaultValue = "NZD") String currency) {
        return ResponseEntity.ok(debtService.summary(currency));
    }

    @GetMapping("/{id}/entries")
    public ResponseEntity<List<DebtEntryDto>> entries(@PathVariable Long id) {
        return ResponseEntity.ok(debtService.entries(id));
    }

    @PostMapping("/{id}/link")
    public ResponseEntity<DebtEntryDto> link(@PathVariable Long id,
                                             @RequestBody @Valid DebtLinkRequest request) {
        return ResponseEntity.ok(debtService.link(id, request));
    }

    @DeleteMapping("/entries/{entryId}")
    public ResponseEntity<Void> unlink(@PathVariable Long entryId) {
        debtService.unlink(entryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/record")
    public ResponseEntity<DebtEntryDto> record(@PathVariable Long id,
                                               @RequestBody @Valid DebtRecordRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(debtService.record(id, request));
    }

    @GetMapping("/{id}/schedule")
    public ResponseEntity<List<LoanScheduleItemDto>> schedule(@PathVariable Long id) {
        return ResponseEntity.ok(debtService.schedule(id));
    }

    @PostMapping("/{id}/schedule")
    public ResponseEntity<ScheduleImportResult> importSchedule(
            @PathVariable Long id,
            @RequestParam String parserId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(debtService.importSchedule(id, parserId, file));
    }

    @GetMapping("/schedule-parsers")
    public ResponseEntity<List<ParserInfo>> scheduleParsers() {
        return ResponseEntity.ok(scheduleParserService.availableParsers());
    }

    @PostMapping("/payments/confirm")
    public ResponseEntity<DebtEntryDto> confirmPayment(@RequestBody @Valid LoanConfirmRequest request) {
        return ResponseEntity.ok(debtService.confirmPayment(request));
    }

    @PostMapping("/schedule-items/{id}/skip")
    public ResponseEntity<Void> skip(@PathVariable Long id) {
        debtService.setScheduleItemSkipped(id, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/schedule-items/{id}/skip")
    public ResponseEntity<Void> unskip(@PathVariable Long id) {
        debtService.setScheduleItemSkipped(id, false);
        return ResponseEntity.noContent().build();
    }
}
