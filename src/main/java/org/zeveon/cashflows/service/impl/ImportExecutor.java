package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Bank;
import org.zeveon.cashflows.entity.ImportRun;
import org.zeveon.cashflows.entity.StatementAccountSkip;
import org.zeveon.cashflows.entity.enums.AccountType;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.model.dto.ImportDtos.*;
import org.zeveon.cashflows.repository.*;
import org.zeveon.cashflows.statement.CategoryRuleMatcher;
import org.zeveon.cashflows.statement.StatementDto;
import org.zeveon.cashflows.statement.StatementTransformer;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Repository-only import core: suggestions for the mapping step and the actual
 * confirmed run. Kept free of the parser/holder so integration tests can drive
 * it with a fixture StatementDto directly.
 *
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class ImportExecutor {

    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final TransactionRepository transactionRepository;
    private final StatementAccountSkipRepository skipRepository;
    private final ImportRunRepository importRunRepository;

    private final StatementTransformer transformer = new StatementTransformer();

    @Transactional(readOnly = true)
    public List<PreviewAccount> suggestAccounts(Bank bank, StatementDto statement) {
        var bankAccounts = accountRepository.findAll().stream()
                .filter(account -> account.getBank() != null
                        && account.getBank().getId().equals(bank.getId()))
                .toList();
        var skips = skipRepository.findByBankId(bank.getId()).stream()
                .map(StatementAccountSkip::getAccountNumber)
                .collect(Collectors.toSet());

        return statement.accounts().stream()
                .map(statementAccount -> {
                    var matched = matchByNumber(bankAccounts, statementAccount.account());
                    AccountSuggestion suggestion;
                    if (matched != null) {
                        suggestion = new AccountSuggestion(SuggestionKind.AUTO,
                                matched.getId(), matched.getName());
                    } else if (skips.contains(statementAccount.account())) {
                        suggestion = new AccountSuggestion(SuggestionKind.SKIP, null, null);
                    } else {
                        suggestion = new AccountSuggestion(SuggestionKind.UNMAPPED, null, null);
                    }
                    return new PreviewAccount(statementAccount.account(), statementAccount.currency(),
                            statementAccount.transactions().size(),
                            statementAccount.summary() == null ? null : statementAccount.summary().balanceStart(),
                            statementAccount.summary() == null ? null : statementAccount.summary().balanceEnd(),
                            suggestion);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CashCurrency> suggestCashTargets(StatementDto statement) {
        Map<String, Integer> withdrawalsByCurrency = new TreeMap<>();
        statement.accounts().forEach(account -> account.transactions().stream()
                .filter(row -> "Withdrawals".equals(row.operation()))
                .forEach(row -> withdrawalsByCurrency.merge(row.currency(), 1, Integer::sum)));

        var cashAccounts = accountRepository.findAll().stream()
                .filter(account -> account.getType() == AccountType.CASH && !account.isArchived())
                .toList();

        return withdrawalsByCurrency.entrySet().stream()
                .map(entry -> {
                    var candidates = cashAccounts.stream()
                            .filter(account -> account.getCurrency().equals(entry.getKey()))
                            .toList();
                    var single = candidates.size() == 1 ? candidates.getFirst() : null;
                    return new CashCurrency(entry.getKey(), entry.getValue(),
                            single == null ? null : single.getId(),
                            single == null ? null : single.getName());
                })
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportReport execute(Bank bank, String fileName, StatementDto statement,
                                ConfirmRequest request) {
        var statementByNumber = statement.accounts().stream()
                .collect(Collectors.toMap(StatementDto.StatementAccount::account, a -> a));

        // 1) account decisions
        var mapped = new LinkedHashMap<String, Account>();
        var skipped = new HashSet<String>();
        for (var decision : request.accounts()) {
            var statementAccount = statementByNumber.get(decision.accountNumber());
            if (statementAccount == null) {
                throw new ConflictException("Statement has no account " + decision.accountNumber());
            }
            switch (decision.action()) {
                case MAP -> {
                    var account = accountRepository.findById(decision.accountId())
                            .orElseThrow(() -> NotFoundException.of("Account", decision.accountId()));
                    if (account.getAccountNumber() == null || account.getAccountNumber().isBlank()) {
                        account.setAccountNumber(decision.accountNumber());  // remembered, FR-4.4
                    }
                    mapped.put(decision.accountNumber(), account);
                    skipRepository.findByBankIdAndAccountNumber(bank.getId(), decision.accountNumber())
                            .ifPresent(skipRepository::delete);
                }
                case CREATE -> {
                    var spec = decision.newAccount();
                    if (spec == null) {
                        throw new ConflictException("CREATE decision needs newAccount");
                    }
                    var summary = statementAccount.summary();
                    var account = accountRepository.save(Account.builder()
                            .name(spec.name())
                            .currency(statementAccount.currency())
                            .type(AccountType.BANK)
                            .bank(bank)
                            .accountNumber(decision.accountNumber())
                            // FR-11.2: opening balance from the statement's own summary
                            .startBalance(orZero(spec.startBalance() != null ? spec.startBalance()
                                    : summary == null ? null : summary.balanceStart()))
                            .startDate(spec.startDate() != null ? spec.startDate()
                                    : statement.statement().periodFrom())
                            .archived(false)
                            .build());
                    mapped.put(decision.accountNumber(), account);
                }
                case SKIP -> {
                    skipped.add(decision.accountNumber());
                    if (skipRepository.findByBankIdAndAccountNumber(bank.getId(),
                            decision.accountNumber()).isEmpty()) {
                        skipRepository.save(StatementAccountSkip.builder()
                                .bank(bank)
                                .accountNumber(decision.accountNumber())
                                .build());
                    }
                }
            }
        }

        // 2) cash targets (FR-11.3)
        var cashAccounts = new HashMap<String, Account>();
        for (var decision : request.cash() == null ? List.<CashDecision>of() : request.cash()) {
            if (decision.createNew()) {
                cashAccounts.put(decision.currency(), accountRepository.save(Account.builder()
                        .name("Cash " + decision.currency())
                        .currency(decision.currency())
                        .type(AccountType.CASH)
                        .startBalance(BigDecimal.ZERO)
                        .startDate(statement.statement().periodFrom())
                        .archived(false)
                        .build()));
            } else if (decision.accountId() != null) {
                cashAccounts.put(decision.currency(), accountRepository.findById(decision.accountId())
                        .orElseThrow(() -> NotFoundException.of("Account", decision.accountId())));
            }
        }

        // 3) transform + dedup + insert
        var balancesBefore = balances();
        var feesCategory = categoryRepository.findByNameAndType("fees", CategoryType.EXPENSE)
                .orElse(null);
        var matcher = CategoryRuleMatcher.of(categoryRuleRepository.findByArchivedFalseOrderByIdAsc());
        var context = new StatementTransformer.Context(mapped, skipped, cashAccounts,
                feesCategory, matcher, transactionRepository.findAllImportKeys());
        var result = transformer.transform(statement, context);

        var run = importRunRepository.save(ImportRun.builder()
                .bank(bank)
                .fileName(fileName)
                .periodFrom(statement.statement().periodFrom())
                .periodTo(statement.statement().periodTo())
                .createdCount(result.transactions().size())
                .duplicateCount(result.duplicates())
                .needsReviewCount(result.needsReview())
                .transferCount(result.transfers())
                .build());
        result.transactions().forEach(transaction -> transaction.setImportRun(run));
        transactionRepository.saveAll(result.transactions());
        transactionRepository.flush();

        var balancesAfter = balances();
        var reportAccounts = new ArrayList<ReportAccount>();
        var reported = new LinkedHashSet<Account>(mapped.values());
        reported.addAll(cashAccounts.values());
        for (var account : reported) {
            reportAccounts.add(new ReportAccount(account.getId(), account.getName(),
                    account.getCurrency(),
                    balancesBefore.getOrDefault(account.getId(), account.getStartBalance()),
                    balancesAfter.getOrDefault(account.getId(), account.getStartBalance())));
        }
        return new ImportReport(run.getId(), result.transactions().size(), result.duplicates(),
                result.needsReview(), result.transfers(), result.warnings(), reportAccounts);
    }

    private Map<Long, BigDecimal> balances() {
        return accountRepository.findAllBalances().stream()
                .collect(Collectors.toMap(AccountRepository.AccountBalance::getId,
                        AccountRepository.AccountBalance::getBalance));
    }

    private static Account matchByNumber(List<Account> accounts, String statementNumber) {
        for (var account : accounts) {
            var number = account.getAccountNumber();
            if (number == null || number.isBlank()) {
                continue;
            }
            if (number.equalsIgnoreCase(statementNumber)) {
                return account;
            }
            // user-entered numbers may be partial ("KZ61…3905"): last-4 suffix match
            var last4 = statementNumber.length() >= 4
                    ? statementNumber.substring(statementNumber.length() - 4) : statementNumber;
            if (number.endsWith(last4)) {
                return account;
            }
        }
        return null;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
