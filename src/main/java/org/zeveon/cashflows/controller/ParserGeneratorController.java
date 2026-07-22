package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zeveon.cashflows.generator.ParserGeneratorService;
import org.zeveon.cashflows.model.dto.GeneratorDtos.GeneratorOptions;
import org.zeveon.cashflows.model.dto.GeneratorDtos.GeneratorSettingsDto;
import org.zeveon.cashflows.model.dto.GeneratorDtos.StartResponse;
import org.zeveon.cashflows.model.dto.GeneratorDtos.TaskSnapshot;
import org.zeveon.cashflows.service.GeneratorSettingsService;

import java.util.List;
import java.util.UUID;

/**
 * AI parser generator API (FR-4.10): options/settings, start, live SSE log
 * with reattach, cancel. The events endpoint is a GET so the browser's
 * EventSource works without CSRF plumbing (the session cookie authenticates).
 *
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/parser-generator")
@RequiredArgsConstructor
public class ParserGeneratorController {

    private final ParserGeneratorService generatorService;
    private final GeneratorSettingsService settingsService;

    @GetMapping("/options")
    public ResponseEntity<GeneratorOptions> options() {
        return ResponseEntity.ok(generatorService.options());
    }

    @GetMapping("/settings")
    public ResponseEntity<GeneratorSettingsDto> settings() {
        return ResponseEntity.ok(settingsService.get());
    }

    @PutMapping("/settings")
    public ResponseEntity<GeneratorSettingsDto> updateSettings(@RequestBody @Valid GeneratorSettingsDto request) {
        return ResponseEntity.ok(settingsService.update(request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<StartResponse> start(@RequestParam("files") List<MultipartFile> files,
                                               @RequestParam String bankName,
                                               @RequestParam String scriptName) {
        return ResponseEntity.accepted().body(generatorService.start(files, bankName, scriptName));
    }

    @GetMapping("/current")
    public ResponseEntity<TaskSnapshot> current() {
        return generatorService.current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{taskId}/events")
    public SseEmitter events(@PathVariable UUID taskId) {
        return generatorService.events(taskId);
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID taskId) {
        generatorService.cancel(taskId);
        return ResponseEntity.noContent().build();
    }
}
