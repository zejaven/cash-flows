package org.zeveon.cashflows.statement;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parses the redacted real-statement fixture (440 rows, 4 accounts) into the DTO.
 *
 * @author Stanislav Vafin
 */
class StatementDtoParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("redacted fixture parses with all rows, summaries and exchange rates")
    void parseFixture() throws IOException {
        StatementDto dto;
        try (var stream = getClass().getResourceAsStream("/statements/alatau-redacted.json")) {
            dto = MAPPER.readValue(stream, StatementDto.class);
        }

        assertThat(dto.bank().name()).isEqualTo("Alatau City Bank");
        assertThat(dto.statement().periodFrom()).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(dto.statement().periodTo()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(dto.accounts()).hasSize(4);

        var total = dto.accounts().stream().mapToInt(a -> a.transactions().size()).sum();
        assertThat(total).isEqualTo(440);

        var usd = dto.accounts().stream()
                .filter(a -> a.currency().equals("USD"))
                .findFirst().orElseThrow();
        assertThat(usd.summary().balanceStart()).isEqualByComparingTo("2701.62");
        assertThat(usd.summary().balanceEnd()).isEqualByComparingTo("7215.08");

        var withRate = dto.accounts().stream()
                .flatMap(a -> a.transactions().stream())
                .filter(row -> row.exchangeRate() != null)
                .toList();
        assertThat(withRate).hasSize(32);
        assertThat(withRate.getFirst().exchangeRate().from()).isNotBlank();

        // every row has the fields the import key is built from
        assertThat(dto.accounts().stream().flatMap(a -> a.transactions().stream()))
                .allSatisfy(row -> {
                    assertThat(row.date()).isNotNull();
                    assertThat(row.time()).isNotNull();
                    assertThat(row.operation()).isNotBlank();
                    assertThat(row.income()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                    assertThat(row.expense()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                });
    }
}
