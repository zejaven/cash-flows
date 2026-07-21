package org.zeveon.cashflows.statement;

import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.statement.StatementDto.Row;
import org.zeveon.cashflows.statement.StatementDto.StatementAccount;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Pure statement-to-transactions transformation (no persistence, unit-testable).
 * <p>
 * Own transfers appear in the statement as TWO rows with different references:
 * the source row ({@code Transfers "Transfer to own account <number>"}, amount in
 * the TARGET currency, expense in its own) and the receiving row
 * ({@code Replenishment "Transfer from own account…"}). They are paired across
 * the whole file by (target account, date, income == source amount, nearest time)
 * and merged into a single TRANSFER carrying both dedup keys.
 *
 * @author Stanislav Vafin
 */
public class StatementTransformer {

    private static final Pattern OWN_TRANSFER_TARGET =
            Pattern.compile("Transfer to own account (\\S+)");
    private static final String OWN_TRANSFER_INCOMING = "Transfer from own account";

    /**
     * @param mappedAccounts statement account number -> app account (MAP and CREATE)
     * @param skippedNumbers statement account numbers the user chose to skip
     * @param cashAccounts   withdrawal currency -> cash account (FR-11.3)
     * @param feesCategory   category for Commission/Fee rows (assigned by operation,
     *                       not through rules)
     * @param existingKeys   import keys already present in the DB, both transfer sides
     */
    public record Context(
            Map<String, Account> mappedAccounts,
            Set<String> skippedNumbers,
            Map<String, Account> cashAccounts,
            Category feesCategory,
            CategoryRuleMatcher matcher,
            Set<String> existingKeys) {
    }

    public record Result(List<Transaction> transactions, int duplicates, int needsReview,
                         int transfers, List<String> warnings) {
    }

    public Result transform(StatementDto statement, Context context) {
        var transactions = new ArrayList<Transaction>();
        var warnings = new ArrayList<String>();
        int duplicates = 0;
        int transfers = 0;

        var pairs = matchOwnTransferPairs(statement, warnings);
        var consumed = new HashSet<Row>();
        pairs.forEach(pair -> {
            consumed.add(pair.source().row());
            consumed.add(pair.target().row());
        });

        // Own-transfer pairs (emitted once, regardless of account iteration order)
        for (var pair : pairs) {
            var sourceMapped = context.mappedAccounts().get(pair.source().number());
            var targetMapped = context.mappedAccounts().get(pair.target().number());
            var sourceKey = importKey(pair.source().number(), pair.source().row());
            var targetKey = importKey(pair.target().number(), pair.target().row());
            if (context.existingKeys().contains(sourceKey)
                    || context.existingKeys().contains(targetKey)) {
                duplicates++;
                continue;
            }
            if (sourceMapped != null && targetMapped != null) {
                var transfer = base(pair.source().row(), sourceMapped, TransactionType.TRANSFER, sourceKey);
                transfer.setAmount(pair.source().row().expense());
                transfer.setCounterAccount(targetMapped);
                transfer.setCounterAmount(pair.source().row().amount());
                transfer.setCounterImportKey(targetKey);
                transactions.add(transfer);
                transfers++;
            } else if (sourceMapped != null) {
                // target skipped: keep the outflow or the balance won't add up (edge case 6)
                var expense = base(pair.source().row(), sourceMapped, TransactionType.EXPENSE, sourceKey);
                expense.setAmount(pair.source().row().expense());
                transactions.add(expense);
                warnings.add("Transfer to skipped account %s on %s imported as expense (needs review)"
                        .formatted(pair.target().number(), pair.source().row().date()));
            } else if (targetMapped != null) {
                var income = base(pair.target().row(), targetMapped, TransactionType.INCOME, targetKey);
                income.setAmount(pair.target().row().income());
                transactions.add(income);
                warnings.add("Transfer from skipped account %s on %s imported as income (needs review)"
                        .formatted(pair.source().number(), pair.target().row().date()));
            }
            // both skipped: nothing to import
        }

        // Remaining rows per mapped account
        for (var statementAccount : statement.accounts()) {
            var account = context.mappedAccounts().get(statementAccount.account());
            if (account == null) {
                continue;
            }
            for (var row : statementAccount.transactions()) {
                if (consumed.contains(row)) {
                    continue;
                }
                var key = importKey(statementAccount.account(), row);
                if (context.existingKeys().contains(key)) {
                    duplicates++;
                    continue;
                }
                var transaction = emitRow(statementAccount, account, row, key, context, warnings);
                if (transaction != null) {
                    transactions.add(transaction);
                    if (transaction.getType() == TransactionType.TRANSFER) {
                        transfers++;
                    }
                }
            }
        }

        var needsReview = (int) transactions.stream()
                .filter(t -> t.getType() != TransactionType.TRANSFER && t.getCategory() == null)
                .count();
        return new Result(transactions, duplicates, needsReview, transfers, warnings);
    }

    private Transaction emitRow(StatementAccount statementAccount, Account account, Row row,
                                String key, Context context, List<String> warnings) {
        switch (row.operation()) {
            case "Commission", "Fee" -> {
                var expense = base(row, account, TransactionType.EXPENSE, key);
                expense.setAmount(amountInAccountCurrency(row));
                expense.setCategory(context.feesCategory());
                withOriginal(expense, row, account);
                return expense;
            }
            case "Withdrawals" -> {
                var cash = context.cashAccounts().get(row.currency());
                if (cash == null) {
                    warnings.add("No cash account chosen for %s withdrawal on %s — imported as expense"
                            .formatted(row.currency(), row.date()));
                    var expense = base(row, account, TransactionType.EXPENSE, key);
                    expense.setAmount(row.expense());
                    return expense;
                }
                var transfer = base(row, account, TransactionType.TRANSFER, key);
                transfer.setAmount(row.expense());
                transfer.setCounterAccount(cash);
                transfer.setCounterAmount(row.amount());
                return transfer;
            }
            default -> {
                if (row.description() != null && OWN_TRANSFER_TARGET.matcher(row.description()).find()) {
                    warnings.add("Unmatched own-transfer row on %s (%s) — imported as plain transaction"
                            .formatted(row.date(), statementAccount.account()));
                }
                var isExpense = row.expense().signum() > 0;
                var type = isExpense ? TransactionType.EXPENSE : TransactionType.INCOME;
                var transaction = base(row, account, type, key);
                transaction.setAmount(amountInAccountCurrency(row));
                withOriginal(transaction, row, account);
                context.matcher().match(row.description(), type)
                        .ifPresent(transaction::setCategory);
                return transaction;
            }
        }
    }

    // --- pair matching ---

    private record AccountRow(String number, Row row) {
    }

    private record TransferPair(AccountRow source, AccountRow target) {
    }

    private List<TransferPair> matchOwnTransferPairs(StatementDto statement, List<String> warnings) {
        var byNumber = new HashMap<String, StatementAccount>();
        statement.accounts().forEach(a -> byNumber.put(a.account(), a));

        var pairs = new ArrayList<TransferPair>();
        var claimed = new HashSet<Row>();

        for (var statementAccount : statement.accounts()) {
            for (var row : statementAccount.transactions()) {
                if (!"Transfers".equals(row.operation()) || row.description() == null) {
                    continue;
                }
                var matcher = OWN_TRANSFER_TARGET.matcher(row.description());
                if (!matcher.find()) {
                    continue;
                }
                var target = resolveStatementAccount(byNumber, matcher.group(1));
                if (target == null) {
                    warnings.add("Own-transfer target %s not in statement (row %s)"
                            .formatted(matcher.group(1), row.date()));
                    continue;
                }
                // receiving candidate: same date, income equal to the source row's
                // amount (already in the target currency), nearest by time
                var candidate = target.transactions().stream()
                        .filter(r -> !claimed.contains(r))
                        .filter(r -> "Replenishment".equals(r.operation()))
                        .filter(r -> r.description() != null
                                && r.description().startsWith(OWN_TRANSFER_INCOMING))
                        .filter(r -> r.date().equals(row.date()))
                        .filter(r -> r.income().compareTo(row.amount()) == 0)
                        .min(Comparator.comparing(r -> timeDistance(row, r)));
                if (candidate.isPresent()) {
                    claimed.add(candidate.get());
                    pairs.add(new TransferPair(
                            new AccountRow(statementAccount.account(), row),
                            new AccountRow(target.account(), candidate.get())));
                }
                // no candidate -> the row falls through to plain emission with a warning
            }
        }
        return pairs;
    }

    private static StatementAccount resolveStatementAccount(Map<String, StatementAccount> byNumber,
                                                            String token) {
        var exact = byNumber.get(token);
        if (exact != null) {
            return exact;
        }
        var suffix = token.length() >= 4 ? token.substring(token.length() - 4) : token;
        return byNumber.values().stream()
                .filter(a -> a.account().endsWith(suffix))
                .findFirst()
                .orElse(null);
    }

    private static Duration timeDistance(Row a, Row b) {
        if (a.time() == null || b.time() == null) {
            return Duration.ofDays(1);
        }
        return Duration.between(a.time(), b.time()).abs();
    }

    // --- helpers ---

    private static Transaction base(Row row, Account account, TransactionType type, String key) {
        return Transaction.builder()
                .type(type)
                .txnDate(row.date())
                .txnTime(row.time())
                .account(account)
                .description(normalize(row.description()))
                .reference(row.reference())
                .authCode(row.authCode())
                .importKey(key)
                .source(TransactionSource.IMPORT)
                .build();
    }

    private static void withOriginal(Transaction transaction, Row row, Account account) {
        if (row.currency() != null && !row.currency().equals(account.getCurrency())) {
            transaction.setOriginalCurrency(row.currency());
            transaction.setOriginalAmount(row.amount());
        }
    }

    private static BigDecimal amountInAccountCurrency(Row row) {
        return row.expense().signum() > 0 ? row.expense() : row.income();
    }

    private static String normalize(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    /**
     * Immutable dedup key of a statement row (FR-4.5): survives user edits of the
     * created transaction, so re-imports of overlapping periods stay idempotent.
     */
    public static String importKey(String statementAccountNumber, Row row) {
        var isDebit = row.expense().signum() > 0;
        var amount = (isDebit ? row.expense() : row.income()).stripTrailingZeros().toPlainString();
        return String.join("|",
                statementAccountNumber,
                String.valueOf(row.date()),
                row.time() == null ? "" : String.valueOf(row.time()),
                isDebit ? "D" : "C",
                amount,
                row.reference() == null ? "" : row.reference(),
                row.authCode() == null ? "" : row.authCode());
    }
}
