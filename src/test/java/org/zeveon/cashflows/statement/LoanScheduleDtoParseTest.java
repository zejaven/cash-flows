package org.zeveon.cashflows.statement;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson mapping of the schedule-parser JSON contract
 * (parsers/schedules/CONTRACT.md): snake_case keys, unknown fields ignored.
 *
 * @author Stanislav Vafin
 */
class LoanScheduleDtoParseTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("parses the contract JSON with snake_case keys and unknown fields")
    void parse() throws Exception {
        var json = """
                {
                  "source": "PaymentSchedule.pdf",
                  "currency": "RUB",
                  "unknown_field": true,
                  "items": [
                    { "position": 1, "due_date": "2021-08-30",
                      "principal": 0.0, "interest": 7796.71,
                      "total": 7796.71, "remaining": 3060000.0 },
                    { "position": 210, "due_date": "2039-01-28",
                      "principal": 15978.06, "interest": 40.71,
                      "total": 16018.77, "remaining": 0.0 }
                  ]
                }
                """;

        var dto = mapper.readValue(json, LoanScheduleDto.class);

        assertThat(dto.currency()).isEqualTo("RUB");
        assertThat(dto.items()).hasSize(2);
        var first = dto.items().getFirst();
        assertThat(first.position()).isEqualTo(1);
        assertThat(first.dueDate()).isEqualTo(LocalDate.of(2021, 8, 30));
        assertThat(first.principal()).isEqualByComparingTo("0");
        assertThat(first.interest()).isEqualByComparingTo("7796.71");
        assertThat(dto.items().getLast().remaining()).isEqualByComparingTo("0");
    }
}
