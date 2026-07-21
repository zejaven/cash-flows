package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.BankDto;
import org.zeveon.cashflows.model.dto.BankRequest;
import org.zeveon.cashflows.service.BankService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/banks")
@RequiredArgsConstructor
public class BankController {

    private final BankService bankService;

    @GetMapping
    public ResponseEntity<List<BankDto>> findAll() {
        return ResponseEntity.ok(bankService.findAll());
    }

    @PostMapping
    public ResponseEntity<BankDto> create(@RequestBody @Valid BankRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bankService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BankDto> update(@PathVariable Long id, @RequestBody @Valid BankRequest request) {
        return ResponseEntity.ok(bankService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bankService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
