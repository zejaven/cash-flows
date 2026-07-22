package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.GeneratorDtos.GeneratorSettingsDto;

/**
 * @author Stanislav Vafin
 */
public interface GeneratorSettingsService {

    GeneratorSettingsDto get();

    GeneratorSettingsDto update(GeneratorSettingsDto request);
}
