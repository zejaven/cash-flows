package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.model.dto.ReconciliationDtos.*;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.ReconciliationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class ReconciliationServiceImpl implements ReconciliationService {

    private static final String CORRECTION = "Correction";
    private static final String DESCRIPTION = "Balance reconciliation";

    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationRow> rows(LocalDate date) {
        Map<Long, BigDecimal> balances = accountRepository.findAllBalancesAsOf(date).stream()
                .collect(Collectors.toMap(AccountRepository.AccountBalance::getId,
                        AccountRepository.AccountBalance::getBalance));
        return accountRepository.findAll().stream()
                .filter(account -> !account.isArchived())
                .map(account -> new ReconciliationRow(account.getId(), account.getName(),
                        account.getCurrency(),
                        balances.getOrDefault(account.getId(), account.getStartBalance())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CorrectionDto> reconcile(ReconciliationRequest request) {
        Map<Long, BigDecimal> computed = accountRepository.findAllBalancesAsOf(request.date()).stream()
                .collect(Collectors.toMap(AccountRepository.AccountBalance::getId,
                        AccountRepository.AccountBalance::getBalance));
        var corrections = new ArrayList<CorrectionDto>();
        for (var entry : request.entries()) {
            var account = accountRepository.findById(entry.accountId())
                    .orElseThrow(() -> NotFoundException.of("Account", entry.accountId()));
            var diff = entry.actualBalance()
                    .subtract(computed.getOrDefault(account.getId(), account.getStartBalance()));
            if (diff.signum() == 0) {
                continue;
            }
            var type = diff.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME;
            var categoryType = type == TransactionType.EXPENSE ? CategoryType.EXPENSE : CategoryType.INCOME;
            var correctionCategory = categoryRepository
                    .findByNameAndTypeAndSystemTrue(CORRECTION, categoryType)
                    .orElseThrow(() -> new ConflictException(
                            "System category Correction/%s is missing".formatted(categoryType)));
            var transaction = transactionRepository.save(Transaction.builder()
                    .type(type)
                    .txnDate(request.date())
                    .account(account)
                    .amount(diff.abs())
                    .category(correctionCategory)
                    .description(DESCRIPTION)
                    .source(TransactionSource.MANUAL)
                    .build());
            corrections.add(new CorrectionDto(transaction.getId(), account.getId(),
                    account.getName(), account.getCurrency(), type, diff.abs()));
        }
        return corrections;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoryEntry> history() {
        var byDate = new LinkedHashMap<LocalDate, List<CorrectionDto>>();
        for (var transaction : transactionRepository.findByCategorySystemTrueOrderByTxnDateDesc()) {
            byDate.computeIfAbsent(transaction.getTxnDate(), d -> new ArrayList<>())
                    .add(new CorrectionDto(transaction.getId(), transaction.getAccount().getId(),
                            transaction.getAccount().getName(), transaction.getAccount().getCurrency(),
                            transaction.getType(), transaction.getAmount()));
        }
        return byDate.entrySet().stream()
                .map(entry -> new HistoryEntry(entry.getKey(), entry.getValue()))
                .toList();
    }
}
