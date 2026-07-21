package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.AveragingProfileDtos.ComputedValue;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.PreviewRequest;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.ProfileDto;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.ProfileRequest;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface AveragingProfileService {

    List<ProfileDto> findAll();

    ProfileDto create(ProfileRequest request);

    ProfileDto update(Long id, ProfileRequest request);

    void delete(Long id);

    /** Live "Computed result" for the editor without saving (mockup 4a). */
    ComputedValue preview(PreviewRequest request);
}
