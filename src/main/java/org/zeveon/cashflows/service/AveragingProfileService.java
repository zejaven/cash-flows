package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.AveragingProfileDtos.ComputedValue;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.PreviewRequest;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.ProfileDto;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.ProfileRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code today} is the client's date (timezone rule) — it anchors ROLLING windows.
 *
 * @author Stanislav Vafin
 */
public interface AveragingProfileService {

    List<ProfileDto> findAll(LocalDate today);

    ProfileDto create(ProfileRequest request, LocalDate today);

    ProfileDto update(Long id, ProfileRequest request, LocalDate today);

    void delete(Long id);

    /** Live "Computed result" for the editor without saving (mockup 4a). */
    ComputedValue preview(PreviewRequest request, LocalDate today);
}
