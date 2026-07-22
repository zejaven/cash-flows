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
            new ImportParserProperties("python", "parsers", Duration.ofMinutes(2)));

    @Test
    @DisplayName("parses the real Alatau statement PDF via subprocess")
    void parseRealPdf() {
        var pdf = Path.of("docs", "Alatau City Bank", "Bank statement-2026-07-20 185719.pdf");
        assertThat(Files.exists(pdf)).isTrue();

        var dto = service.parse("alatau", pdf);

        assertThat(dto.accounts()).hasSize(4);
        assertThat(dto.accounts().stream().mapToInt(a -> a.transactions().size()).sum())
                .isEqualTo(440);
    }

    @Test
    @DisplayName("parses a real BNZ YouMoney statement via subprocess")
    void parseBnzPdf() {
        var pdf = Path.of("docs", "BNZ", "YouMoney_2026-03-12_1.pdf");
        assertThat(Files.exists(pdf)).isTrue();

        var dto = service.parse("bnz", pdf);

        assertThat(dto.bank().name()).isEqualTo("BNZ");
        assertThat(dto.accounts()).hasSize(1);
        var account = dto.accounts().getFirst();
        assertThat(account.currency()).isEqualTo("NZD");
        assertThat(account.transactions()).hasSize(7);
        assertThat(account.summary().balanceEnd()).isEqualByComparingTo("40.20");
        // synthesized references keep same-day equal amounts dedup-distinct
        assertThat(account.transactions().stream().map(row -> row.reference()).distinct())
                .hasSize(7);
    }

    @Test
    @DisplayName("parses a ByBit asset-history export (zip of CSVs) via subprocess")
    void parseBybitExport() {
        var zip = Path.of("docs", "ByBit", "assetHistory_869355_20260121_20260721.zip");
        assertThat(Files.exists(zip)).isTrue();

        var dto = service.parse("bybit", zip);

        assertThat(dto.bank().name()).isEqualTo("ByBit");
        var account = dto.accounts().getFirst();
        assertThat(account.currency()).isEqualTo("USD");
        // 39 payments + 36 fee rows + 1 deposit + 1 withdraw
        assertThat(account.transactions()).hasSize(77);
        assertThat(account.transactions().stream().map(row -> row.reference()).distinct())
                .hasSize(77);
        assertThat(account.summary().balanceStart()).isNull();
    }

    @Test
    @DisplayName("unknown parser id -> conflict")
    void unknownParser() {
        assertThatThrownBy(() -> service.parse("nope", Path.of("whatever.pdf")))
                .hasMessageContaining("Unknown statement parser");
    }
}
