package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.AccountDto;
import org.zeveon.cashflows.model.dto.AccountRequest;
import org.zeveon.cashflows.service.AccountService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping
    public ResponseEntity<List<AccountDto>> findAll(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ResponseEntity.ok(accountService.findAll(includeArchived));
    }

    @PostMapping
    public ResponseEntity<AccountDto> create(@RequestBody @Valid AccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AccountDto> update(@PathVariable Long id,
                                             @RequestBody @Valid AccountRequest request) {
        return ResponseEntity.ok(accountService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
