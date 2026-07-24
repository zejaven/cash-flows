package org.zeveon.cashflows.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.zeveon.cashflows.config.ImportGeneratorProperties;
import org.zeveon.cashflows.config.ImportParserProperties;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.model.dto.GeneratorDtos.GeneratorSettingsDto;
import org.zeveon.cashflows.service.GeneratorSettingsService;
import org.zeveon.cashflows.statement.StatementParserService;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Guard rails of the generation entry point (FR-4.10): everything that must be
 * rejected before any process is spawned.
 *
 * @author Stanislav Vafin
 */
@ExtendWith(MockitoExtension.class)
class ParserGeneratorServiceImplTest {

    @Mock
    private GeneratorSettingsService settingsService;

    @Mock
    private AgentCliRunner cliRunner;

    @Mock
    private GeneratorPromptBuilder promptBuilder;

    @Mock
    private StatementParserService parserService;

    private final MockMultipartFile pdf =
            new MockMultipartFile("files", "statement.pdf", "application/pdf", new byte[]{1});

    private ParserGeneratorServiceImpl service(boolean enabled) {
        var properties = new ImportGeneratorProperties(enabled, Duration.ofMinutes(15),
                ".generator-tmp", "prompts/generate-parser.md",
                new ImportGeneratorProperties.Cli("claude", List.of("", "claude-fable-5")),
                new ImportGeneratorProperties.Cli("codex", List.of("")));
        var parserProperties = new ImportParserProperties("python", "parsers", null,
                Duration.ofMinutes(2));
        return new ParserGeneratorServiceImpl(properties, parserProperties, settingsService,
                cliRunner, promptBuilder, parserService);
    }

    @Test
    @DisplayName("disabled environment -> conflict")
    void disabled() {
        assertThatThrownBy(() -> service(false).start(List.of(pdf), "T-Bank", "tbank"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("disabled");
        assertThat(service(false).options().enabled()).isFalse();
    }

    @Test
    @DisplayName("script name must be a slug")
    void badScriptName() {
        var service = service(true);
        for (var name : new String[]{null, "", "T-Bank", "a", "../evil", "a b", "x.py"}) {
            assertThatThrownBy(() -> service.start(List.of(pdf), "T-Bank", name))
                    .as("scriptName: %s", name)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("slug");
        }
    }

    @Test
    @DisplayName("no files / empty upload / non-PDF upload -> conflict")
    void badFile() {
        var service = service(true);
        assertThatThrownBy(() -> service.start(List.of(), "T-Bank", "tbank"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("at least one");

        var empty = new MockMultipartFile("files", "statement.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> service.start(List.of(pdf, empty), "T-Bank", "tbank"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("empty");

        var txt = new MockMultipartFile("files", "statement.txt", "text/plain", new byte[]{1});
        assertThatThrownBy(() -> service.start(List.of(pdf, txt), "T-Bank", "tbank"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(".pdf/.csv/.zip");
    }

    @Test
    @DisplayName("more samples than the cap -> conflict")
    void tooManySamples() {
        var service = service(true);
        var files = java.util.Collections.<org.springframework.web.multipart.MultipartFile>nCopies(11, pdf);
        assertThatThrownBy(() -> service.start(files, "T-Bank", "tbank"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("At most");
    }

    @Test
    @DisplayName("existing script -> conflict (runs against the real parsers/ dir)")
    void existingScript() {
        assertThatThrownBy(() -> service(true).start(List.of(pdf), "Alatau", "alatau"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("unavailable CLI -> conflict with the probe error")
    void cliUnavailable() {
        when(settingsService.get())
                .thenReturn(new GeneratorSettingsDto(AgentProvider.CLAUDE, null, null));
        when(cliRunner.resolve("claude")).thenReturn(
                new AgentCliRunner.ResolvedCommand(false, "claude", null, "claude: not found"));

        assertThatThrownBy(() -> service(true).start(List.of(pdf), "T-Bank", "definitely-new-bank"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not available");
    }
}
