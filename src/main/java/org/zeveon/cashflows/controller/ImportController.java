package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.zeveon.cashflows.model.dto.ImportDtos.ConfirmRequest;
import org.zeveon.cashflows.model.dto.ImportDtos.ImportPreview;
import org.zeveon.cashflows.model.dto.ImportDtos.ImportReport;
import org.zeveon.cashflows.model.dto.ImportDtos.ImportRunDto;
import org.zeveon.cashflows.service.ImportService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @PostMapping("/preview")
    public ResponseEntity<ImportPreview> preview(@RequestParam Long bankId,
                                                 @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(importService.preview(bankId, file));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ImportReport> confirm(@RequestBody @Valid ConfirmRequest request) {
        return ResponseEntity.ok(importService.confirm(request));
    }

    @GetMapping
    public ResponseEntity<List<ImportRunDto>> history() {
        return ResponseEntity.ok(importService.history());
    }
}
