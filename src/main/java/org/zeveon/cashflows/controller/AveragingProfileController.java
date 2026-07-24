package org.zeveon.cashflows.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.ComputedValue;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.PreviewRequest;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.ProfileDto;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.ProfileRequest;
import org.zeveon.cashflows.service.AveragingProfileService;

import java.time.LocalDate;
import java.util.List;

/**
 * "Today" is always the client's date (timezone rule) — it anchors ROLLING windows.
 *
 * @author Stanislav Vafin
 */
@RestController
@RequestMapping("/api/averaging-profiles")
@RequiredArgsConstructor
public class AveragingProfileController {

    private final AveragingProfileService profileService;

    @GetMapping
    public ResponseEntity<List<ProfileDto>> findAll(@RequestParam LocalDate today) {
        return ResponseEntity.ok(profileService.findAll(today));
    }

    @PostMapping
    public ResponseEntity<ProfileDto> create(@RequestBody @Valid ProfileRequest request,
                                             @RequestParam LocalDate today) {
        return ResponseEntity.status(HttpStatus.CREATED).body(profileService.create(request, today));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProfileDto> update(@PathVariable Long id,
                                             @RequestBody @Valid ProfileRequest request,
                                             @RequestParam LocalDate today) {
        return ResponseEntity.ok(profileService.update(id, request, today));
    }

    @PatchMapping("/{id}/forecast-visible")
    public ResponseEntity<ProfileDto> setForecastVisible(@PathVariable Long id,
                                                         @RequestParam boolean visible,
                                                         @RequestParam LocalDate today) {
        return ResponseEntity.ok(profileService.setForecastVisible(id, visible, today));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        profileService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/preview")
    public ResponseEntity<ComputedValue> preview(@RequestBody @Valid PreviewRequest request,
                                                 @RequestParam LocalDate today) {
        return ResponseEntity.ok(profileService.preview(request, today));
    }
}
