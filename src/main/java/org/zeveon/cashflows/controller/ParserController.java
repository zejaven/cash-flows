package org.zeveon.cashflows.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zeveon.cashflows.statement.ParserInfo;
import org.zeveon.cashflows.statement.StatementParserService;

import java.util.List;

/**
 * Lists statement parsers available in the scripts directory (FR-4.2) so the
 * Settings UI can offer a dropdown instead of a free-text parser id.
 *
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/parsers")
@RequiredArgsConstructor
public class ParserController {

    private final StatementParserService parserService;

    @GetMapping
    public ResponseEntity<List<ParserInfo>> list() {
        return ResponseEntity.ok(parserService.availableParsers());
    }
}
