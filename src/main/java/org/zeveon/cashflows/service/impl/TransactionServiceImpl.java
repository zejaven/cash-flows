package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.mapper.TransactionMapper;
import org.zeveon.cashflows.model.TransactionFilter;
import org.zeveon.cashflows.model.dto.BulkCategoryRequest;
import org.zeveon.cashflows.model.dto.ConvertToTransferRequest;
import org.zeveon.cashflows.model.dto.PageDto;
import org.zeveon.cashflows.model.dto.TransactionDto;
import org.zeveon.cashflows.model.dto.TransactionRequest;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.DebtEntryRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.repository.spec.TransactionSpecifications;
import org.zeveon.cashflows.service.TransactionService;

import java.util.stream.Collectors;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final DebtEntryRepository debtEntryRepository;

    @Override
    @Transactional(readOnly = true)
    public PageDto<TransactionDto> find(TransactionFilter filter, int page, int size) {
        var pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("txnDate"), Sort.Order.desc("id")));
        var result = transactionRepository.findAll(TransactionSpecifications.matching(filter), pageable);
        // debt links resolved in one query — no @OneToOne on Transaction (lazy
        // optional one-to-one degrades to N+1 without bytecode enhancement)
        var entryByTxnId = debtEntryRepository
                .findByTransactionIdIn(result.getContent().stream().map(Transaction::getId).toList())
                .stream()
                .collect(Collectors.toMap(entry -> entry.getTransaction().getId(), entry -> entry));
        return new PageDto<>(result.getContent().stream()
                .map(transaction -> TransactionMapper.INSTANCE.toDto(transaction,
                        entryByTxnId.get(transaction.getId())))
                .toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransactionDto create(TransactionRequest request) {
        var transaction = new Transaction();
        transaction.setSource(TransactionSource.MANUAL);
        apply(transaction, request);
        return TransactionMapper.INSTANCE.toDto(transactionRepository.save(transaction));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransactionDto update(Long id, TransactionRequest request) {
        var transaction = transactionRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Transaction", id));
        var entry = debtEntryRepository.findByTransactionId(id).orElse(null);
        if (entry != null) {
            if (request.type() != transaction.getType()) {
                throw new ConflictException(
                        "Transaction is a debt movement of '%s' — unlink it before changing the type"
                                .formatted(entry.getDebt().getName()));
            }
            if (request.amount().compareTo(entry.getTxnPrincipal()) < 0) {
                throw new ConflictException(
                        "Amount is below the linked debt principal %s — unlink from '%s' first"
                                .formatted(entry.getTxnPrincipal(), entry.getDebt().getName()));
            }
        }
        apply(transaction, request);
        return TransactionMapper.INSTANCE.toDto(transaction, entry);
    }

    /**
     * A statement row's import key names one side of the money movement: the
     * spending side goes to {@code importKey}, the receiving side to
     * {@code counterImportKey}. Keeping both keys (the edited row's and the
     * absorbed counterpart's) is what keeps overlapping re-imports idempotent.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransactionDto convertToTransfer(Long id, ConvertToTransferRequest request) {
        var transaction = transactionRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Transaction", id));
        if (transaction.getType() == TransactionType.TRANSFER) {
            throw new ConflictException("Transaction is already a transfer");
        }
        requireNotDebtLinked(transaction.getId());
        if (request.counterAccountId().equals(request.accountId())) {
            throw new IllegalArgumentException("Transfer must go to a different account");
        }
        var sendingKey = transaction.getType() == TransactionType.EXPENSE ? transaction.getImportKey() : null;
        var receivingKey = transaction.getType() == TransactionType.INCOME ? transaction.getImportKey() : null;
        if (request.counterTransactionId() != null) {
            var counterpart = absorbCounterpart(transaction, request);
            if (counterpart.getType() == TransactionType.EXPENSE) {
                sendingKey = counterpart.getImportKey();
            } else {
                receivingKey = counterpart.getImportKey();
            }
        }
        transaction.setType(TransactionType.TRANSFER);
        transaction.setTxnDate(request.date());
        transaction.setAccount(resolveAccount(request.accountId()));
        transaction.setAmount(request.amount());
        transaction.setCategory(null);
        transaction.setDescription(request.description());
        transaction.setOriginalCurrency(null);
        transaction.setOriginalAmount(null);
        transaction.setCounterAccount(resolveAccount(request.counterAccountId()));
        transaction.setCounterAmount(request.counterAmount());
        transaction.setImportKey(sendingKey);
        transaction.setCounterImportKey(receivingKey);
        return TransactionMapper.INSTANCE.toDto(transaction);
    }

    private Transaction absorbCounterpart(Transaction transaction, ConvertToTransferRequest request) {
        var counterpart = transactionRepository.findById(request.counterTransactionId())
                .orElseThrow(() -> NotFoundException.of("Transaction", request.counterTransactionId()));
        if (counterpart.getId().equals(transaction.getId())) {
            throw new IllegalArgumentException("Counterpart must be a different transaction");
        }
        if (counterpart.getType() == TransactionType.TRANSFER
                || counterpart.getType() == transaction.getType()) {
            throw new ConflictException("Counterpart must be the opposite side: %s expects %s"
                    .formatted(transaction.getType(),
                            transaction.getType() == TransactionType.EXPENSE
                                    ? TransactionType.INCOME : TransactionType.EXPENSE));
        }
        var expectedAccountId = counterpart.getType() == TransactionType.EXPENSE
                ? request.accountId() : request.counterAccountId();
        if (!counterpart.getAccount().getId().equals(expectedAccountId)) {
            throw new ConflictException("Counterpart transaction is on the wrong account");
        }
        requireNotDebtLinked(counterpart.getId());
        transactionRepository.delete(counterpart);
        // Hibernate flushes updates before deletes; force the delete out now, or the
        // converted row inherits the counterpart's (account_id, import_key) while the
        // counterpart still exists and trips uq_transaction_import_key.
        transactionRepository.flush();
        return counterpart;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var transaction = transactionRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Transaction", id));
        transactionRepository.delete(transaction);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bulkCategory(BulkCategoryRequest request) {
        var category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> NotFoundException.of("Category", request.categoryId()));
        var transactions = transactionRepository.findAllById(request.transactionIds());
        if (transactions.size() != request.transactionIds().size()) {
            throw new NotFoundException("Some transactions not found");
        }
        for (var transaction : transactions) {
            if (transaction.getType() == TransactionType.TRANSFER) {
                throw new ConflictException("Transfers cannot have a category");
            }
            requireMatchingType(transaction.getType(), category);
            transaction.setCategory(category);
        }
    }

    private void requireNotDebtLinked(Long transactionId) {
        debtEntryRepository.findByTransactionId(transactionId).ifPresent(entry -> {
            throw new ConflictException("Transaction is a debt movement of '%s' — unlink it first"
                    .formatted(entry.getDebt().getName()));
        });
    }

    private void apply(Transaction transaction, TransactionRequest request) {
        validate(request);
        transaction.setType(request.type());
        transaction.setTxnDate(request.date());
        transaction.setAccount(resolveAccount(request.accountId()));
        transaction.setAmount(request.amount());
        transaction.setCategory(resolveCategory(request));
        transaction.setDescription(request.description());
        transaction.setOriginalCurrency(normalizeCurrency(request.originalCurrency()));
        transaction.setOriginalAmount(request.originalAmount());
        if (request.type() == TransactionType.TRANSFER) {
            transaction.setCounterAccount(resolveAccount(request.counterAccountId()));
            transaction.setCounterAmount(request.counterAmount());
        } else {
            transaction.setCounterAccount(null);
            transaction.setCounterAmount(null);
        }
    }

    private void validate(TransactionRequest request) {
        var isTransfer = request.type() == TransactionType.TRANSFER;
        if (isTransfer) {
            if (request.counterAccountId() == null || request.counterAmount() == null) {
                throw new IllegalArgumentException("Transfer requires counterAccountId and counterAmount");
            }
            if (request.counterAccountId().equals(request.accountId())) {
                throw new IllegalArgumentException("Transfer must go to a different account");
            }
            if (request.categoryId() != null) {
                throw new IllegalArgumentException("Transfer cannot have a category");
            }
        } else if (request.counterAccountId() != null || request.counterAmount() != null) {
            throw new IllegalArgumentException("Counter fields are only allowed for transfers");
        }
        if ((request.originalCurrency() == null) != (request.originalAmount() == null)) {
            throw new IllegalArgumentException("originalCurrency and originalAmount must come together");
        }
    }

    private Category resolveCategory(TransactionRequest request) {
        if (request.categoryId() == null) {
            return null;
        }
        var category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> NotFoundException.of("Category", request.categoryId()));
        requireMatchingType(request.type(), category);
        return category;
    }

    private static void requireMatchingType(TransactionType transactionType, Category category) {
        var expected = transactionType == TransactionType.EXPENSE ? CategoryType.EXPENSE : CategoryType.INCOME;
        if (category.getType() != expected) {
            throw new ConflictException("Category '%s' is %s, transaction is %s"
                    .formatted(category.getName(), category.getType(), transactionType));
        }
    }

    private Account resolveAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("Account", accountId));
    }

    private static String normalizeCurrency(String currency) {
        return currency == null ? null : currency.toUpperCase();
    }
}
