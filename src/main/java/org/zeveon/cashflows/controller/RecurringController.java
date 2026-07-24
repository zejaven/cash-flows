package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.RecurringDtos.*;
import org.zeveon.cashflows.service.RecurringService;

import java.time.LocalDate;
import java.util.List;

/**
 * "Today" is always the client's date (timezone rule) — hence the explicit params.
 *
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/recurring")
@RequiredArgsConstructor
public class RecurringController {

    private final RecurringService recurringService;

    @GetMapping
    public ResponseEntity<List<RecurringRuleDto>> findAll(@RequestParam LocalDate today) {
        return ResponseEntity.ok(recurringService.findAll(today));
    }

    @PostMapping
    public ResponseEntity<RecurringRuleDto> create(@RequestBody @Valid RecurringRuleRequest request,
                                                   @RequestParam LocalDate today) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(recurringService.create(request, today));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RecurringRuleDto> update(@PathVariable Long id,
                                                   @RequestBody @Valid RecurringRuleRequest request,
                                                   @RequestParam LocalDate today) {
        return ResponseEntity.ok(recurringService.update(id, request, today));
    }

    @PatchMapping("/{id}/forecast-visible")
    public ResponseEntity<RecurringRuleDto> setForecastVisible(@PathVariable Long id,
                                                               @RequestParam boolean visible,
                                                               @RequestParam LocalDate today) {
        return ResponseEntity.ok(recurringService.setForecastVisible(id, visible, today));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        recurringService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/queue")
    public ResponseEntity<List<QueueItemDto>> queue(@RequestParam LocalDate today) {
        return ResponseEntity.ok(recurringService.queue(today));
    }

    @PostMapping("/confirm")
    public ResponseEntity<OccurrenceDto> confirm(@RequestBody @Valid ConfirmRequest request) {
        return ResponseEntity.ok(recurringService.confirm(request));
    }

    @PostMapping("/skip")
    public ResponseEntity<OccurrenceDto> skip(@RequestBody @Valid SkipRequest request) {
        return ResponseEntity.ok(recurringService.skip(request));
    }

    @GetMapping("/{id}/occurrences")
    public ResponseEntity<List<OccurrenceDto>> occurrences(@PathVariable Long id) {
        return ResponseEntity.ok(recurringService.occurrences(id));
    }

    @DeleteMapping("/occurrences/{id}")
    public ResponseEntity<Void> deleteOccurrence(@PathVariable Long id) {
        recurringService.deleteOccurrence(id);
        return ResponseEntity.noContent().build();
    }
}
