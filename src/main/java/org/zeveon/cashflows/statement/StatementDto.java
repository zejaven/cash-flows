package org.zeveon.cashflows.statement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * JSON contract of parse_statement.py (docs/bank-statement-structure.md).
 * Snake_case keys; unknown fields (holder etc.) are ignored.
 *
 * @author Stanislav Vafin
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StatementDto(
        String source,
        BankInfo bank,
        StatementInfo statement,
        List<StatementAccount> accounts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BankInfo(String name, String bic) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatementInfo(String type, String generatedAt,
                                LocalDate periodFrom, LocalDate periodTo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatementAccount(String account, String currency,
                                   Summary summary, List<Row> transactions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Summary(BigDecimal balanceStart, BigDecimal balanceEnd,
                          BigDecimal deposits, BigDecimal transfers, BigDecimal purchases,
                          BigDecimal withdrawals, BigDecimal fees, BigDecimal others,
                          BigDecimal amountInProcess, BigDecimal balanceTotal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Row(LocalDate date, LocalTime time, String operation, String description,
                      String reference, String authCode, ExchangeRate exchangeRate,
                      BigDecimal amount, String currency, BigDecimal income, BigDecimal expense) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExchangeRate(BigDecimal rate, String from, String to) {
    }
}
