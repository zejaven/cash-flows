package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.CounterpartyDto;
import org.zeveon.cashflows.model.dto.CounterpartyRequest;
import org.zeveon.cashflows.service.CounterpartyService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/counterparties")
@RequiredArgsConstructor
public class CounterpartyController {

    private final CounterpartyService counterpartyService;

    @GetMapping
    public ResponseEntity<List<CounterpartyDto>> findAll(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(counterpartyService.findAll(includeArchived));
    }

    @PostMapping
    public ResponseEntity<CounterpartyDto> create(@RequestBody @Valid CounterpartyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(counterpartyService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CounterpartyDto> update(@PathVariable Long id,
                                                  @RequestBody @Valid CounterpartyRequest request) {
        return ResponseEntity.ok(counterpartyService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        counterpartyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
