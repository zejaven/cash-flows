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
import org.zeveon.cashflows.model.dto.PageDto;
import org.zeveon.cashflows.model.dto.TransactionDto;
import org.zeveon.cashflows.model.dto.TransactionRequest;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.repository.spec.TransactionSpecifications;
import org.zeveon.cashflows.service.TransactionService;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;

    @Override
    @Transactional(readOnly = true)
    public PageDto<TransactionDto> find(TransactionFilter filter, int page, int size) {
        var pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("txnDate"), Sort.Order.desc("id")));
        var result = transactionRepository.findAll(TransactionSpecifications.matching(filter), pageable);
        return new PageDto<>(result.getContent().stream().map(TransactionMapper.INSTANCE::toDto).toList(),
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
        apply(transaction, request);
        return TransactionMapper.INSTANCE.toDto(transaction);
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
