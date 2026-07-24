package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.PlannedTransaction;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.entity.enums.PlannedStatus;
import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.model.dto.PlannedDtos.PlannedConfirmRequest;
import org.zeveon.cashflows.model.dto.PlannedDtos.PlannedDto;
import org.zeveon.cashflows.model.dto.PlannedDtos.PlannedRequest;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.PlannedTransactionRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.PlannedTransactionService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class PlannedTransactionServiceImpl implements PlannedTransactionService {

    private final PlannedTransactionRepository plannedRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PlannedDto> findAll() {
        return plannedRepository.findAllByOrderByDueDateAsc().stream()
                .map(PlannedTransactionServiceImpl::toDto)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlannedDto create(PlannedRequest request) {
        validate(request);
        var planned = new PlannedTransaction();
        planned.setStatus(PlannedStatus.PENDING);
        apply(planned, request);
        return toDto(plannedRepository.save(planned));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlannedDto update(Long id, PlannedRequest request) {
        validate(request);
        var planned = pending(id);
        apply(planned, request);
        return toDto(planned);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlannedDto setForecastVisible(Long id, boolean visible) {
        var planned = plannedRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Planned transaction", id));
        planned.setForecastVisible(visible);
        return toDto(planned);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        plannedRepository.delete(plannedRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Planned transaction", id)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlannedDto confirm(Long id, PlannedConfirmRequest request) {
        var planned = pending(id);
        var amount = request.amount() != null ? request.amount() : planned.getAmount();
        var counterAmount = planned.getType() == TransactionType.TRANSFER
                ? (request.counterAmount() != null ? request.counterAmount() : planned.getCounterAmount())
                : null;
        if (planned.getType() == TransactionType.TRANSFER && counterAmount == null) {
            throw new IllegalArgumentException("Transfer confirmation needs a counter amount");
        }
        var transaction = transactionRepository.save(Transaction.builder()
                .type(planned.getType())
                .txnDate(request.date() != null ? request.date() : planned.getDueDate())
                .account(planned.getAccount())
                .amount(amount)
                .category(planned.getCategory())
                .description(planned.getDescription())
                .counterAccount(planned.getCounterAccount())
                .counterAmount(counterAmount)
                .source(TransactionSource.MANUAL)
                .build());
        planned.setStatus(PlannedStatus.CONFIRMED);
        planned.setTransaction(transaction);
        return toDto(planned);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlannedDto dismiss(Long id) {
        var planned = pending(id);
        planned.setStatus(PlannedStatus.DISMISSED);
        return toDto(planned);
    }

    // --- helpers ---

    private PlannedTransaction pending(Long id) {
        var planned = plannedRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Planned transaction", id));
        if (planned.getStatus() != PlannedStatus.PENDING) {
            throw new ConflictException("Planned transaction is already " + planned.getStatus());
        }
        return planned;
    }

    private void validate(PlannedRequest request) {
        var isTransfer = request.type() == TransactionType.TRANSFER;
        if (isTransfer) {
            if (request.counterAccountId() == null) {
                throw new IllegalArgumentException("Planned transfer needs counterAccountId");
            }
            if (request.counterAccountId().equals(request.accountId())) {
                throw new IllegalArgumentException("Transfer must go to a different account");
            }
            if (request.categoryId() != null) {
                throw new IllegalArgumentException("Planned transfer cannot have a category");
            }
        } else if (request.counterAccountId() != null || request.counterAmount() != null) {
            throw new IllegalArgumentException("Counter fields are only for transfers");
        }
    }

    private void apply(PlannedTransaction planned, PlannedRequest request) {
        planned.setType(request.type());
        planned.setDueDate(request.dueDate());
        planned.setAccount(resolveAccount(request.accountId()));
        planned.setCounterAccount(request.counterAccountId() == null ? null
                : resolveAccount(request.counterAccountId()));
        planned.setAmount(request.amount());
        planned.setCounterAmount(request.type() == TransactionType.TRANSFER
                ? request.counterAmount() : null);
        planned.setCategory(resolveCategory(request));
        planned.setDescription(request.description());
    }

    private Account resolveAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("Account", accountId));
    }

    private Category resolveCategory(PlannedRequest request) {
        if (request.categoryId() == null) {
            return null;
        }
        var category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> NotFoundException.of("Category", request.categoryId()));
        var expected = request.type() == TransactionType.EXPENSE
                ? CategoryType.EXPENSE : CategoryType.INCOME;
        if (category.getType() != expected) {
            throw new ConflictException("Category '%s' is %s, planned is %s"
                    .formatted(category.getName(), category.getType(), request.type()));
        }
        if (category.isSystem()) {
            throw new ConflictException("Planned transactions cannot use a system category");
        }
        return category;
    }

    private static PlannedDto toDto(PlannedTransaction planned) {
        return new PlannedDto(planned.getId(), planned.getType(), planned.getDueDate(),
                planned.getAccount().getId(), planned.getAccount().getName(),
                planned.getAccount().getCurrency(),
                planned.getCounterAccount() == null ? null : planned.getCounterAccount().getId(),
                planned.getCounterAccount() == null ? null : planned.getCounterAccount().getName(),
                planned.getCounterAccount() == null ? null : planned.getCounterAccount().getCurrency(),
                planned.getAmount(), planned.getCounterAmount(),
                planned.getCategory() == null ? null : planned.getCategory().getId(),
                planned.getCategory() == null ? null : planned.getCategory().getName(),
                planned.getCategory() == null ? null : planned.getCategory().getColor(),
                planned.getDescription(), planned.getStatus(), planned.isForecastVisible(),
                planned.getTransaction() == null ? null : planned.getTransaction().getId());
    }
}
