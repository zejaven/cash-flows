package org.zeveon.cashflows.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @author Stanislav Vafin
 */
@ConfigurationProperties(prefix = "import.parser")
public record ImportParserProperties(String pythonCommand, String scriptsDir,
                                     String scheduleScriptsDir, Duration timeout) {

    public ImportParserProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(120);
        }
        if (scheduleScriptsDir == null || scheduleScriptsDir.isBlank()) {
            scheduleScriptsDir = "parsers/schedules";
        }
    }
}
