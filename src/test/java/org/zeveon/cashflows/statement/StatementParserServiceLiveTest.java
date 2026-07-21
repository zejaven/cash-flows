package org.zeveon.cashflows.statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.zeveon.cashflows.config.ImportParserProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live subprocess test: runs the actual Python parser over the real PDF in docs/.
 * Needs local python + pdfplumber — tagged integration, excluded from the default
 * build; run with -Dexcluded.test.groups=
 *
 * @author Stanislav Vafin
 */
@Tag("integration")
class StatementParserServiceLiveTest {

    private final StatementParserService service = new StatementParserServiceImpl(
            new ImportParserProperties("python", "parse_statement.py", Duration.ofMinutes(2)));

    @Test
    @DisplayName("parses the real statement PDF via subprocess")
    void parseRealPdf() {
        var pdf = Path.of("docs", "Bank statement-2026-07-20 185719.pdf");
        assertThat(Files.exists(pdf)).isTrue();

        var dto = service.parse(StatementParserServiceImpl.ALATAU_PARSER_ID, pdf);

        assertThat(dto.accounts()).hasSize(4);
        assertThat(dto.accounts().stream().mapToInt(a -> a.transactions().size()).sum())
                .isEqualTo(440);
    }

    @Test
    @DisplayName("unknown parser id -> conflict")
    void unknownParser() {
        assertThatThrownBy(() -> service.parse("nope", Path.of("whatever.pdf")))
                .hasMessageContaining("Unknown statement parser");
    }
}
