package org.zeveon.cashflows.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @author Stanislav Vafin
 */
@ConfigurationProperties(prefix = "import.parser")
public record ImportParserProperties(String pythonCommand, String scriptsDir, Duration timeout) {

    public ImportParserProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(120);
        }
    }
}
