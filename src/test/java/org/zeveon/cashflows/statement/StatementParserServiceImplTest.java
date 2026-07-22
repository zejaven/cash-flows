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
 * Verifies the convention-based parser registry (FR-4.2): script discovery and
 * the parser-id validation that doubles as the path-traversal gate.
 *
 * @author Stanislav Vafin
 */
class StatementParserServiceImplTest {

    @TempDir
    Path scriptsDir;

    private StatementParserService service() {
        return new StatementParserServiceImpl(
                new ImportParserProperties("python", scriptsDir.toString(), Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("availableParsers lists slug-named .py scripts by stem, sorted")
    void availableParsers() throws IOException {
        Files.createFile(scriptsDir.resolve("bnz.py"));
        Files.createFile(scriptsDir.resolve("alatau.py"));
        Files.createFile(scriptsDir.resolve("my_bank-2.py"));
        Files.createFile(scriptsDir.resolve("README.md"));
        Files.createFile(scriptsDir.resolve("Bad Name.py"));
        Files.createFile(scriptsDir.resolve("_private.py"));

        assertThat(service().availableParsers()).containsExactly(
                new ParserInfo("alatau", "alatau.py"),
                new ParserInfo("bnz", "bnz.py"),
                new ParserInfo("my_bank-2", "my_bank-2.py"));
    }

    @Test
    @DisplayName("availableParsers is empty when the scripts dir is missing")
    void availableParsersMissingDir() {
        var service = new StatementParserServiceImpl(new ImportParserProperties(
                "python", scriptsDir.resolve("nope").toString(), Duration.ofSeconds(5)));
        assertThat(service.availableParsers()).isEmpty();
    }

    @Test
    @DisplayName("rejects ids that are malformed, traversal attempts or unknown")
    void rejectsBadParserIds() throws IOException {
        Files.createFile(scriptsDir.resolve("alatau.py"));
        var service = service();
        var pdf = Path.of("whatever.pdf");

        for (var id : new String[]{
                null, "", "alatau ", "Alatau", "ALATAU",
                "../evil", "..", "a/b", "a\\b", "alatau.py",
                "missing", "alatau-pdf"}) {
            assertThatThrownBy(() -> service.parse(id, pdf))
                    .as("id: %s", id)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Unknown statement parser");
        }
    }
}
