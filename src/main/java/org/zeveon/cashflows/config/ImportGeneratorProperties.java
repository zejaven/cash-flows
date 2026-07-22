package org.zeveon.cashflows.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.zeveon.cashflows.generator.AgentProvider;

import java.time.Duration;
import java.util.List;

/**
 * AI parser generator configuration (FR-4.10). The CLIs are host-local npm
 * tools, so the feature is disabled in Docker via {@code enabled: false}.
 *
 * @author Stanislav Vafin
 */
@ConfigurationProperties(prefix = "import.generator")
public record ImportGeneratorProperties(
        boolean enabled,
        Duration timeout,
        String workDir,
        String promptFile,
        Cli claude,
        Cli codex) {

    public ImportGeneratorProperties {
        if (timeout == null) {
            timeout = Duration.ofMinutes(15);
        }
        if (workDir == null || workDir.isBlank()) {
            workDir = ".generator-tmp";
        }
        if (promptFile == null || promptFile.isBlank()) {
            promptFile = "prompts/generate-parser.md";
        }
    }

    /**
     * One CLI: base command (resolved against Windows npm shims at runtime) and
     * the offered model ids; blank means the CLI's own default.
     */
    public record Cli(String command, List<String> models) {

        public Cli {
            if (models == null) {
                models = List.of("");
            }
        }
    }

    public Cli cli(AgentProvider provider) {
        return provider == AgentProvider.CLAUDE ? claude : codex;
    }
}
