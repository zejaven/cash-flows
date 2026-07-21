package org.zeveon.cashflows.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.zeveon.cashflows.entity.Bank;
import org.zeveon.cashflows.model.dto.ImportDtos.*;
import org.zeveon.cashflows.repository.*;
import org.zeveon.cashflows.service.impl.ImportExecutor;
import org.zeveon.cashflows.statement.StatementDto;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the full confirmed import twice over the redacted real-statement fixture
 * (440 rows). Needs the compose postgres (cash_flows_test DB) — tagged
 * integration; run with -Dexcluded.test.groups=
 *
 * @author Stanislav Vafin
 */
@Tag("integration")
@DataJpaTest
@Import(ImportExecutor.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5434/cash_flows_test",
        "spring.datasource.username=${DB_USERNAME:cashflows}",
        "spring.datasource.password=${DB_PASSWORD:cashflows-local}",
})
class ImportExecutorIntegrationTest {

    @Autowired
    private ImportExecutor importExecutor;

    @Autowired
    private BankRepository bankRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private StatementAccountSkipRepository skipRepository;

    @Autowired
    private ImportRunRepository importRunRepository;

    private StatementDto statement;
    private Bank bank;

    @BeforeEach
    void setUp() throws IOException {
        transactionRepository.deleteAll();
        importRunRepository.deleteAll();
        skipRepository.deleteAll();
        accountRepository.deleteAll();
        bankRepository.deleteAll();

        var mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (var stream = getClass().getResourceAsStream("/statements/alatau-redacted.json")) {
            statement = mapper.readValue(stream, StatementDto.class);
        }
        bank = bankRepository.save(Bank.builder()
                .name("Alatau City Bank")
                .parserId("alatau-pdf")
                .build());
    }

    private ConfirmRequest confirmAll() {
        var decisions = statement.accounts().stream()
                .map(account -> switch (account.currency()) {
                    case "RUB" -> new AccountDecision(account.account(), MappingAction.SKIP, null, null);
                    default -> new AccountDecision(account.account(), MappingAction.CREATE, null,
                            new NewAccountSpec("Alatau " + account.currency(), null, null));
                })
                .toList();
        return new ConfirmRequest(null, decisions,
                List.of(new CashDecision("NZD", null, true)));
    }

    @Test
    @DisplayName("first import creates everything and balances match the statement summary; re-run creates nothing")
    void importTwice() {
        var report = importExecutor.execute(bank, "fixture.json", statement, confirmAll());

        // parser-verified statement: created rows minus merged transfer pairs
        assertThat(report.created()).isGreaterThan(400);
        assertThat(report.duplicates()).isZero();
        assertThat(report.transfers()).isGreaterThanOrEqualTo(16); // 16 own pairs + 23 ATM
        assertThat(report.warnings()).isEmpty();

        Map<String, BigDecimal> balances = accountRepository.findAll().stream()
                .collect(Collectors.toMap(a -> a.getName(), a -> balance(a.getId())));
        // FR-11.2: computed balance must equal the statement's own closing balance
        assertThat(balances.get("Alatau KZT")).isEqualByComparingTo("0");
        assertThat(balances.get("Alatau USD")).isEqualByComparingTo("7215.08");
        assertThat(balances.get("Alatau EUR")).isEqualByComparingTo("0");

        // Re-run the way a real second import goes: decisions built from suggestions
        // (accounts now exist with numbers -> AUTO; RUB remembered as SKIP)
        var suggestions = importExecutor.suggestAccounts(bank, statement);
        assertThat(suggestions)
                .filteredOn(s -> !s.currency().equals("RUB"))
                .allSatisfy(s -> assertThat(s.suggestion().kind()).isEqualTo(SuggestionKind.AUTO));
        assertThat(suggestions)
                .filteredOn(s -> s.currency().equals("RUB"))
                .singleElement()
                .satisfies(s -> assertThat(s.suggestion().kind()).isEqualTo(SuggestionKind.SKIP));

        var cash = importExecutor.suggestCashTargets(statement);
        assertThat(cash).singleElement()
                .satisfies(c -> assertThat(c.suggestedAccountId()).isNotNull());

        var secondDecisions = suggestions.stream()
                .map(s -> s.suggestion().kind() == SuggestionKind.AUTO
                        ? new AccountDecision(s.accountNumber(), MappingAction.MAP,
                        s.suggestion().accountId(), null)
                        : new AccountDecision(s.accountNumber(), MappingAction.SKIP, null, null))
                .toList();
        var second = importExecutor.execute(bank, "fixture.json", statement,
                new ConfirmRequest(null, secondDecisions,
                        List.of(new CashDecision("NZD", cash.getFirst().suggestedAccountId(), false))));
        assertThat(second.created()).isZero();
        assertThat(second.duplicates()).isEqualTo(report.created());
    }

    private BigDecimal balance(Long accountId) {
        return accountRepository.findAllBalances().stream()
                .filter(entry -> entry.getId().equals(accountId))
                .map(AccountRepository.AccountBalance::getBalance)
                .findFirst()
                .orElseThrow();
    }
}
