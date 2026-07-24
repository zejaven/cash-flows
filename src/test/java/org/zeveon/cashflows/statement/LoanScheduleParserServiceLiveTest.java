package org.zeveon.cashflows.statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.zeveon.cashflows.config.ImportParserProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live subprocess test over the real RSHB payment schedule in docs/. Needs
 * local python + pdfplumber — tagged integration, run with -Dexcluded.test.groups=
 *
 * @author Stanislav Vafin
 */
@Tag("integration")
class LoanScheduleParserServiceLiveTest {

    private final LoanScheduleParserService service = new LoanScheduleParserServiceImpl(
            new ImportParserProperties("python", "parsers", "parsers/schedules", Duration.ofMinutes(2)));

    @Test
    @DisplayName("parses the real RSHB payment schedule: 210 rows, RUB, verified chain")
    void parseRealSchedule() {
        var pdf = Path.of("docs", "RSHB", "PaymentSchedule.pdf");
        assertThat(Files.exists(pdf)).isTrue();

        var dto = service.parse("rshb", pdf);

        assertThat(dto.currency()).isEqualTo("RUB");
        assertThat(dto.items()).hasSize(210);
        var first = dto.items().getFirst();
        assertThat(first.dueDate()).isEqualTo("2021-08-30");
        assertThat(first.principal()).isEqualByComparingTo("0");
        assertThat(first.remaining()).isEqualByComparingTo("3060000");
        var last = dto.items().getLast();
        assertThat(last.dueDate()).isEqualTo("2039-01-28");
        assertThat(last.remaining()).isEqualByComparingTo("0");
        // principal column totals the contract amount (the parser verified it too)
        assertThat(dto.items().stream()
                .map(LoanScheduleDto.Item::principal)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo("3060000");
    }

    @Test
    @DisplayName("parses the real T-Bank schedule: 59 rows incl. interest-only grace months")
    void parseRealTbankSchedule() {
        var pdf = Path.of("docs", "T-Bank",
                "AIHjla_c4YXKhwHD0_bpyKyTs4EBAg50bXNnXzFlAA_График_платежей.pdf");
        assertThat(Files.exists(pdf)).isTrue();

        var dto = service.parse("tbank", pdf);

        assertThat(dto.currency()).isEqualTo("RUB");
        assertThat(dto.items()).hasSize(59);
        var first = dto.items().getFirst();
        assertThat(first.dueDate()).isEqualTo("2026-08-22");
        assertThat(first.principal()).isEqualByComparingTo("0");      // grace: interest-only
        assertThat(first.total()).isEqualByComparingTo("36990");
        assertThat(dto.items().getLast().remaining()).isEqualByComparingTo("0");
        assertThat(dto.items().stream()
                .map(LoanScheduleDto.Item::principal)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo("922000");
    }
}
