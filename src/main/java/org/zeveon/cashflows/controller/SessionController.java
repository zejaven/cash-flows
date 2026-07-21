package org.zeveon.cashflows.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zeveon.cashflows.model.dto.SessionDto;

/**
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    @GetMapping
    public ResponseEntity<SessionDto> session(Authentication authentication) {
        return ResponseEntity.ok(new SessionDto(authentication.getName()));
    }
}
