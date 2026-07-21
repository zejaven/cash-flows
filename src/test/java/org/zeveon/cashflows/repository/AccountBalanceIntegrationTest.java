package org.zeveon.cashflows.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.AccountType;
import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the computed-balance SQL (FR-1.2): signs by type, both sides of a
 * transfer, and the per-account start-date cutoff.
 * <p>
 * Runs against the docker-compose postgres (port 5434) in a dedicated
 * {@code cash_flows_test} database — start it with {@code docker compose up -d postgres}.
 * (Testcontainers was the original plan, but docker-java cannot talk to
 * Docker Desktop 29.x as of now.) Excluded from the default build; run with
 * {@code -Dexcluded.test.groups=}.
 *
 * @author Stanislav Vafin
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5434/cash_flows_test",
        "spring.datasource.username=${DB_USERNAME:cashflows}",
        "spring.datasource.password=${DB_PASSWORD:cashflows-local}",
})
class AccountBalanceIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void cleanUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    @DisplayName("balance = start + income - expense - transfer out + transfer in, respecting start dates")
    void balancesAcrossTransfer() {
        var start = LocalDate.of(2026, 1, 20);
        var nzd = accountRepository.save(account("Cash NZD", "NZD", new BigDecimal("100"), start));
        var usd = accountRepository.save(account("Alatau USD", "USD", new BigDecimal("50"), start));

        // expense 30 NZD
        transactionRepository.save(transaction(TransactionType.EXPENSE, nzd, "30", start.plusDays(1), null, null));
        // income 10 USD
        transactionRepository.save(transaction(TransactionType.INCOME, usd, "10", start.plusDays(2), null, null));
        // transfer 20 USD -> 33 NZD
        transactionRepository.save(transaction(TransactionType.TRANSFER, usd, "20", start.plusDays(3), nzd, "33"));
        // before NZD start date: must not affect NZD
        transactionRepository.save(transaction(TransactionType.EXPENSE, nzd, "999", start.minusDays(1), null, null));

        Map<Long, BigDecimal> balances = accountRepository.findAllBalances().stream()
                .collect(Collectors.toMap(AccountRepository.AccountBalance::getId,
                        AccountRepository.AccountBalance::getBalance));

        assertThat(balances.get(nzd.getId())).isEqualByComparingTo("103");   // 100 - 30 + 33
        assertThat(balances.get(usd.getId())).isEqualByComparingTo("40");    // 50 + 10 - 20
    }

    private static Account account(String name, String currency, BigDecimal startBalance, LocalDate startDate) {
        return Account.builder()
                .name(name)
                .currency(currency)
                .type(AccountType.CASH)
                .startBalance(startBalance)
                .startDate(startDate)
                .archived(false)
                .build();
    }

    private static Transaction transaction(TransactionType type, Account account, String amount,
                                           LocalDate date, Account counterAccount, String counterAmount) {
        return Transaction.builder()
                .type(type)
                .txnDate(date)
                .account(account)
                .amount(new BigDecimal(amount))
                .counterAccount(counterAccount)
                .counterAmount(counterAmount == null ? null : new BigDecimal(counterAmount))
                .source(TransactionSource.MANUAL)
                .build();
    }
}
