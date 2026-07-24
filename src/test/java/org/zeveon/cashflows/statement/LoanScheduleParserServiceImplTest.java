package org.zeveon.cashflows.statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeveon.cashflows.config.ImportParserProperties;
import org.zeveon.cashflows.exception.ConflictException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registry-by-convention over parsers/schedules (FR-13.5), mirroring
 * {@link StatementParserServiceImplTest} for the schedule-parser sibling.
 *
 * @author Stanislav Vafin
 */
class LoanScheduleParserServiceImplTest {

    @TempDir
    Path scriptsDir;

    private LoanScheduleParserService service() {
        return new LoanScheduleParserServiceImpl(new ImportParserProperties(
                "python", "parsers", scriptsDir.toString(), Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("availableParsers lists slug-named .py scripts from the schedules dir")
    void availableParsers() throws IOException {
        Files.createFile(scriptsDir.resolve("rshb.py"));
        Files.createFile(scriptsDir.resolve("Bad Name.py"));
        Files.createFile(scriptsDir.resolve("CONTRACT.md"));

        assertThat(service().availableParsers())
                .containsExactly(new ParserInfo("rshb", "rshb.py"));
    }

    @Test
    @DisplayName("blank schedule dir config falls back to parsers/schedules default")
    void defaultDir() {
        var properties = new ImportParserProperties("python", "parsers", " ", null);
        assertThat(properties.scheduleScriptsDir()).isEqualTo("parsers/schedules");
    }

    @Test
    @DisplayName("rejects malformed, traversal or unknown parser ids")
    void rejectsBadParserIds() throws IOException {
        Files.createFile(scriptsDir.resolve("rshb.py"));
        var service = service();
        var pdf = Path.of("whatever.pdf");

        for (var id : new String[]{null, "", "RSHB", "../evil", "a\\b", "rshb.py", "missing"}) {
            assertThatThrownBy(() -> service.parse(id, pdf))
                    .as("id: %s", id)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Unknown schedule parser");
        }
    }
}
