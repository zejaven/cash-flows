package org.zeveon.cashflows.statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.CategoryRule;
import org.zeveon.cashflows.entity.enums.AccountType;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.statement.StatementDto.Row;
import org.zeveon.cashflows.statement.StatementDto.StatementAccount;
import org.zeveon.cashflows.statement.StatementTransformer.Context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stanislav Vafin
 */
class StatementTransformerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 25);
    private static final String KZT_NUM = "KZ88998PB00012753904";
    private static final String USD_NUM = "KZ61998PB00012753905";

    private final StatementTransformer transformer = new StatementTransformer();

    private final Account kzt = account(1L, "Alatau KZT", "KZT");
    private final Account usd = account(2L, "Alatau USD", "USD");
    private final Account cashNzd = account(3L, "Cash NZD", "NZD");
    private final Category fees = category(10L, "fees", CategoryType.EXPENSE);
    private final Category groceries = category(11L, "groceries", CategoryType.EXPENSE);

    private static Account account(Long id, String name, String currency) {
        return Account.builder().id(id).name(name).currency(currency).type(AccountType.BANK).build();
    }

    private static Category category(Long id, String name, CategoryType type) {
        return Category.builder().id(id).name(name).type(type).color("#000000").build();
    }

    private static Row row(String operation, String description, String reference,
                           BigDecimal amount, String currency, BigDecimal income, BigDecimal expense,
                           LocalTime time) {
        return new Row(DATE, time, operation, description, reference, "211002", null,
                amount, currency, income, expense);
    }

    private Context context(Map<String, Account> mapped, Set<String> skipped, Set<String> existing) {
        return new Context(mapped, skipped, Map.of("NZD", cashNzd), fees,
                CategoryRuleMatcher.of(List.of()), existing);
    }

    private static StatementDto statement(StatementAccount... accounts) {
        return new StatementDto("test.pdf", new StatementDto.BankInfo("Alatau City Bank", "TSESKZKA"),
                new StatementDto.StatementInfo("Card", null, DATE, DATE), List.of(accounts));
    }

    private static StatementAccount stmtAccount(String number, String currency, Row... rows) {
        return new StatementAccount(number, currency, null, List.of(rows));
    }

    // Real-statement shape: source row carries the target-currency amount; both
    // rows have DIFFERENT references.
    private Row transferSource() {
        return row("Transfers",
                "Transfer to own account " + USD_NUM + " at the Bank's current exchange rate 485.40 KZT = 1 USD",
                "KK9qGvRwcDC2", new BigDecimal("1801.2"), "USD",
                BigDecimal.ZERO, new BigDecimal("874302.48"), LocalTime.of(14, 52, 30));
    }

    private Row transferTarget() {
        return row("Replenishment",
                "Transfer from own account at the Bank's current exchange rate 485.40 KZT = 1 USD",
                "jjZCngKIN8rk", new BigDecimal("1801.2"), "USD",
                new BigDecimal("1801.2"), BigDecimal.ZERO, LocalTime.of(14, 52, 31));
    }

    @Test
    @DisplayName("own-transfer pair merges into one TRANSFER with both dedup keys")
    void ownTransferPair() {
        var statement = statement(
                stmtAccount(KZT_NUM, "KZT", transferSource()),
                stmtAccount(USD_NUM, "USD", transferTarget()));
        var result = transformer.transform(statement,
                context(Map.of(KZT_NUM, kzt, USD_NUM, usd), Set.of(), Set.of()));

        assertThat(result.transactions()).hasSize(1);
        var transfer = result.transactions().getFirst();
        assertThat(transfer.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(transfer.getAccount()).isSameAs(kzt);
        assertThat(transfer.getAmount()).isEqualByComparingTo("874302.48");
        assertThat(transfer.getCounterAccount()).isSameAs(usd);
        assertThat(transfer.getCounterAmount()).isEqualByComparingTo("1801.2");
        assertThat(transfer.getImportKey()).contains(KZT_NUM).contains("D");
        assertThat(transfer.getCounterImportKey()).contains(USD_NUM).contains("C");
        assertThat(result.transfers()).isEqualTo(1);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("either stored key of a transfer marks a re-import as duplicate")
    void transferDedupByEitherKey() {
        var statement = statement(
                stmtAccount(KZT_NUM, "KZT", transferSource()),
                stmtAccount(USD_NUM, "USD", transferTarget()));
        var counterKey = StatementTransformer.importKey(USD_NUM, transferTarget());

        var result = transformer.transform(statement,
                context(Map.of(KZT_NUM, kzt, USD_NUM, usd), Set.of(), Set.of(counterKey)));

        assertThat(result.transactions()).isEmpty();
        assertThat(result.duplicates()).isEqualTo(1);
    }

    @Test
    @DisplayName("transfer to a skipped account becomes a needs-review expense with a warning")
    void transferToSkippedAccount() {
        var statement = statement(
                stmtAccount(KZT_NUM, "KZT", transferSource()),
                stmtAccount(USD_NUM, "USD", transferTarget()));
        var result = transformer.transform(statement,
                context(Map.of(KZT_NUM, kzt), Set.of(USD_NUM), Set.of()));

        assertThat(result.transactions()).hasSize(1);
        var expense = result.transactions().getFirst();
        assertThat(expense.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(expense.getAmount()).isEqualByComparingTo("874302.48");
        assertThat(expense.getCategory()).isNull();
        assertThat(result.needsReview()).isEqualTo(1);
        assertThat(result.warnings()).singleElement().asString().contains("skipped");
    }

    @Test
    @DisplayName("ATM withdrawal becomes a TRANSFER to the cash account; its commission a fees expense")
    void atmWithdrawalAndCommission() {
        var withdrawal = row("Withdrawals", "Cash Withdrawal from Account via ATM: BNZ",
                "000831052390", new BigDecimal("750"), "NZD",
                BigDecimal.ZERO, new BigDecimal("438.30"), LocalTime.of(3, 45, 32));
        var commission = row("Commission", "Cash Withdrawal from Account via ATM: BNZ",
                "000831052390", new BigDecimal("6.50"), "USD",
                BigDecimal.ZERO, new BigDecimal("6.50"), LocalTime.of(3, 45, 32));
        var result = transformer.transform(
                statement(stmtAccount(USD_NUM, "USD", withdrawal, commission)),
                context(Map.of(USD_NUM, usd), Set.of(), Set.of()));

        assertThat(result.transactions()).hasSize(2);
        var transfer = result.transactions().stream()
                .filter(t -> t.getType() == TransactionType.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.getAmount()).isEqualByComparingTo("438.30");
        assertThat(transfer.getCounterAccount()).isSameAs(cashNzd);
        assertThat(transfer.getCounterAmount()).isEqualByComparingTo("750");
        var feeTxn = result.transactions().stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE).findFirst().orElseThrow();
        assertThat(feeTxn.getCategory()).isSameAs(fees);
        assertThat(feeTxn.getReference()).isEqualTo("000831052390");
    }

    @Test
    @DisplayName("Fee + its mirroring Replenishment import as two separate transactions")
    void feeAndMirror() {
        var fee = row("Fee", "Payment of commission: Regular Charge", null,
                new BigDecimal("200"), "KZT", BigDecimal.ZERO, new BigDecimal("200"),
                LocalTime.of(7, 52, 4));
        var mirror = row("Replenishment", "Card service fee: Regular Charge", null,
                new BigDecimal("199.08"), "KZT", new BigDecimal("199.08"), BigDecimal.ZERO,
                LocalTime.of(7, 52, 4));
        var result = transformer.transform(
                statement(stmtAccount(KZT_NUM, "KZT", fee, mirror)),
                context(Map.of(KZT_NUM, kzt), Set.of(), Set.of()));

        assertThat(result.transactions()).hasSize(2);
        assertThat(result.transactions())
                .anySatisfy(t -> {
                    assertThat(t.getType()).isEqualTo(TransactionType.EXPENSE);
                    assertThat(t.getCategory()).isSameAs(fees);
                })
                .anySatisfy(t -> {
                    assertThat(t.getType()).isEqualTo(TransactionType.INCOME);
                    assertThat(t.getCategory()).isNull();
                });
    }

    @Test
    @DisplayName("foreign-currency payment keeps the original pair (FR-2.1)")
    void originalCurrency() {
        var payment = row("Payment", "PAK N SAVE PETONE", null,
                new BigDecimal("42"), "NZD", BigDecimal.ZERO, new BigDecimal("24.57"),
                LocalTime.of(11, 59, 21));
        var result = transformer.transform(
                statement(stmtAccount(USD_NUM, "USD", payment)),
                context(Map.of(USD_NUM, usd), Set.of(), Set.of()));

        var txn = result.transactions().getFirst();
        assertThat(txn.getAmount()).isEqualByComparingTo("24.57");
        assertThat(txn.getOriginalCurrency()).isEqualTo("NZD");
        assertThat(txn.getOriginalAmount()).isEqualByComparingTo("42");
    }

    @Test
    @DisplayName("category rules: | alternatives, case-insensitive, type must match")
    void categoryRules() {
        var rules = List.of(
                CategoryRule.builder().id(1L).pattern("pak n save|COUNTDOWN").category(groceries).build());
        var context = new Context(Map.of(USD_NUM, usd), Set.of(), Map.of(), fees,
                CategoryRuleMatcher.of(rules), Set.of());

        var grocery = row("Payment", "PAK N SAVE ALBANY", null,
                new BigDecimal("42"), "NZD", BigDecimal.ZERO, new BigDecimal("24.57"), LocalTime.NOON);
        var salary = row("Replenishment", "PAK N SAVE REFUND??", null,
                new BigDecimal("10"), "USD", new BigDecimal("10"), BigDecimal.ZERO, LocalTime.NOON);
        var unknown = row("Payment", "PARKABLE LIMITED", null,
                new BigDecimal("4.5"), "NZD", BigDecimal.ZERO, new BigDecimal("2.74"), LocalTime.NOON);

        var result = transformer.transform(
                statement(stmtAccount(USD_NUM, "USD", grocery, salary, unknown)), context);

        assertThat(result.transactions()).hasSize(3);
        assertThat(result.transactions().get(0).getCategory()).isSameAs(groceries);
        // income direction: EXPENSE rule must not fire
        assertThat(result.transactions().get(1).getCategory()).isNull();
        assertThat(result.transactions().get(2).getCategory()).isNull();
        assertThat(result.needsReview()).isEqualTo(2);
    }

    @Test
    @DisplayName("re-running the transform against stored keys yields zero new transactions")
    void idempotency() {
        var statement = statement(
                stmtAccount(KZT_NUM, "KZT", transferSource()),
                stmtAccount(USD_NUM, "USD", transferTarget(),
                        row("Payment", "CAFE FIGG", null, new BigDecimal("30.09"), "NZD",
                                BigDecimal.ZERO, new BigDecimal("17.61"), LocalTime.of(7, 17, 29))));
        var context = context(Map.of(KZT_NUM, kzt, USD_NUM, usd), Set.of(), Set.of());

        var first = transformer.transform(statement, context);
        assertThat(first.transactions()).hasSize(2);

        var storedKeys = new java.util.HashSet<String>();
        first.transactions().forEach(t -> {
            storedKeys.add(t.getImportKey());
            if (t.getCounterImportKey() != null) storedKeys.add(t.getCounterImportKey());
        });

        var second = transformer.transform(statement,
                context(Map.of(KZT_NUM, kzt, USD_NUM, usd), Set.of(), storedKeys));
        assertThat(second.transactions()).isEmpty();
        assertThat(second.duplicates()).isEqualTo(2);
    }

    @Test
    @DisplayName("import key handles null time/reference and trailing zeros")
    void importKeyNullsAndZeros() {
        var noTime = new Row(DATE, null, "Payment", "X", null, null, null,
                new BigDecimal("100.00"), "USD", BigDecimal.ZERO, new BigDecimal("100.00"));
        var key = StatementTransformer.importKey(USD_NUM, noTime);
        assertThat(key).isEqualTo(USD_NUM + "|2026-06-25||D|100||");
    }
}
