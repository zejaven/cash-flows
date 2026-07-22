package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.config.ImportGeneratorProperties;
import org.zeveon.cashflows.entity.GeneratorSettings;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.generator.AgentProvider;
import org.zeveon.cashflows.model.dto.GeneratorDtos.GeneratorSettingsDto;
import org.zeveon.cashflows.repository.GeneratorSettingsRepository;
import org.zeveon.cashflows.service.GeneratorSettingsService;

/**
 * App-wide AI provider/model selection for the parser generator (FR-4.10),
 * persisted as a Liquibase-seeded singleton row. Models are validated against
 * the lists configured in application.yml so a stale UI cannot persist an
 * unknown model id.
 *
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class GeneratorSettingsServiceImpl implements GeneratorSettingsService {

    private final GeneratorSettingsRepository repository;
    private final ImportGeneratorProperties properties;

    @Override
    @Transactional(readOnly = true)
    public GeneratorSettingsDto get() {
        return toDto(settings());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GeneratorSettingsDto update(GeneratorSettingsDto request) {
        validateModel(AgentProvider.CLAUDE, request.claudeModel());
        validateModel(AgentProvider.CODEX, request.codexModel());
        var settings = settings();
        settings.setProvider(request.provider());
        settings.setClaudeModel(normalize(request.claudeModel()));
        settings.setCodexModel(normalize(request.codexModel()));
        return toDto(settings);
    }

    private GeneratorSettings settings() {
        return repository.findById(GeneratorSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "generator_settings row is missing — Liquibase seed not applied"));
    }

    private void validateModel(AgentProvider provider, String model) {
        if (model == null || model.isBlank()) {
            return;
        }
        if (!properties.cli(provider).models().contains(model)) {
            throw new ConflictException("Unknown %s model: %s".formatted(provider, model));
        }
    }

    private static String normalize(String model) {
        return model == null || model.isBlank() ? null : model;
    }

    private static GeneratorSettingsDto toDto(GeneratorSettings settings) {
        return new GeneratorSettingsDto(
                settings.getProvider(), settings.getClaudeModel(), settings.getCodexModel());
    }
}
