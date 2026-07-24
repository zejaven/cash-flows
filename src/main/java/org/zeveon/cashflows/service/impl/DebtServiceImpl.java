package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Debt;
import org.zeveon.cashflows.entity.DebtEntry;
import org.zeveon.cashflows.entity.LoanScheduleItem;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.CategoryType;
import org.zeveon.cashflows.entity.enums.DebtDirection;
import org.zeveon.cashflows.entity.enums.DebtEntryRole;
import org.zeveon.cashflows.entity.enums.DebtKind;
import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.model.dto.DebtDtos.*;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.CounterpartyRepository;
import org.zeveon.cashflows.repository.DebtEntryRepository;
import org.zeveon.cashflows.repository.DebtRepository;
import org.zeveon.cashflows.repository.LoanScheduleItemRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.DebtService;
import org.zeveon.cashflows.service.FxService;
import org.zeveon.cashflows.statement.LoanScheduleParserService;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Debts & loans (FR-13): a debt is settled by plain EXPENSE/INCOME transactions
 * attached through {@link DebtEntry} links. Direction and role fix which
 * transaction type is a valid attachment: borrowing arrives as INCOME and is
 * repaid by EXPENSE; lending leaves as EXPENSE and comes back as INCOME.
 *
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class DebtServiceImpl implements DebtService {

    private final DebtRepository debtRepository;
    private final DebtEntryRepository entryRepository;
    private final LoanScheduleItemRepository scheduleRepository;
    private final CounterpartyRepository counterpartyRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final LoanScheduleParserService scheduleParserService;
    private final FxService fxService;

    @Override
    @Transactional(readOnly = true)
    public List<DebtDto> findAll(boolean includeClosed) {
        var sums = principalSums();
        return debtRepository.findAll().stream()
                .filter(debt -> includeClosed || !debt.isClosed())
                .sorted(Comparator.comparing(Debt::getId))
                .map(debt -> toDto(debt, sums))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DebtDto create(DebtRequest request) {
        var debt = new Debt();
        apply(debt, request);
        return toDto(debtRepository.save(debt), principalSums());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DebtDto update(Long id, DebtRequest request) {
        var debt = debtRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Debt", id));
        if (entryRepository.existsByDebtId(id)) {
            if (debt.getDirection() != request.direction()) {
                throw new ConflictException("Debt has linked transactions — direction cannot change");
            }
            if (!debt.getCurrency().equalsIgnoreCase(request.currency())) {
                throw new ConflictException("Debt has linked transactions — currency cannot change");
            }
        }
        apply(debt, request);
        return toDto(debt, principalSums());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var debt = debtRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Debt", id));
        if (entryRepository.existsByDebtId(id)) {
            throw new ConflictException("Debt has linked transactions — close it instead");
        }
        debtRepository.delete(debt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DebtDto setClosed(Long id, boolean closed) {
        var debt = debtRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Debt", id));
        debt.setClosed(closed);
        return toDto(debt, principalSums());
    }

    @Override
    @Transactional(readOnly = true)
    public DebtSummaryDto summary(String currency) {
        var target = currency.toUpperCase();
        var sums = principalSums();
        var rates = fxService.currentRates();
        var owedToMe = BigDecimal.ZERO;
        var iOwe = BigDecimal.ZERO;
        var approx = false;
        for (var debt : debtRepository.findByClosedFalseOrderByCreatedAtAsc()) {
            var outstanding = outstandingOf(debt, sums);
            BigDecimal converted;
            if (debt.getCurrency().equalsIgnoreCase(target)) {
                converted = outstanding;
            } else {
                converted = rates
                        .flatMap(table -> fxService.convert(outstanding, debt.getCurrency(), target,
                                table.rates()))
                        .orElse(null);
                if (converted == null) {
                    approx = true;
                    continue;
                }
            }
            if (debt.getDirection() == DebtDirection.OWED_TO_ME) {
                owedToMe = owedToMe.add(converted);
            } else {
                iOwe = iOwe.add(converted);
            }
        }
        return new DebtSummaryDto(target,
                owedToMe.setScale(2, RoundingMode.HALF_UP),
                iOwe.setScale(2, RoundingMode.HALF_UP), approx);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebtEntryDto> entries(Long debtId) {
        requireDebt(debtId);
        return entryRepository.findByDebtIdOrderByCreatedAtDesc(debtId).stream()
                .map(DebtServiceImpl::toDto)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DebtEntryDto link(Long debtId, DebtLinkRequest request) {
        var debt = requireOpenDebt(debtId);
        var transaction = transactionRepository.findById(request.transactionId())
                .orElseThrow(() -> NotFoundException.of("Transaction", request.transactionId()));

        LoanScheduleItem item = null;
        var role = request.role();
        if (request.scheduleItemId() != null) {
            item = requireLinkableItem(debt, request.scheduleItemId());
            role = DebtEntryRole.REPAYMENT;
        }
        if (role == null) {
            throw new IllegalArgumentException("role is required when no schedule item is given");
        }

        var txnPrincipal = defaultTxnPrincipal(request.txnPrincipal(), transaction, debt, item);
        var debtPrincipal = defaultDebtPrincipal(request.debtPrincipal(), txnPrincipal,
                transaction, debt, item);
        return toDto(createEntry(debt, transaction, role, txnPrincipal, debtPrincipal, item));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlink(Long entryId) {
        var entry = entryRepository.findById(entryId)
                .orElseThrow(() -> NotFoundException.of("Debt entry", entryId));
        entryRepository.delete(entry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DebtEntryDto record(Long debtId, DebtRecordRequest request) {
        var debt = requireOpenDebt(debtId);
        var account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> NotFoundException.of("Account", request.accountId()));
        var transaction = transactionRepository.save(Transaction.builder()
                .type(expectedType(debt.getDirection(), request.role()))
                .txnDate(request.date())
                .account(account)
                .amount(request.amount())
                .description(request.description() != null ? request.description() : debt.getName())
                .source(TransactionSource.MANUAL)
                .build());
        var txnPrincipal = defaultTxnPrincipal(request.txnPrincipal(), transaction, debt, null);
        var debtPrincipal = defaultDebtPrincipal(request.debtPrincipal(), txnPrincipal,
                transaction, debt, null);
        return toDto(createEntry(debt, transaction, request.role(), txnPrincipal, debtPrincipal, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanScheduleItemDto> schedule(Long debtId) {
        requireDebt(debtId);
        var paidBy = entryRepository.findByDebtIdOrderByCreatedAtDesc(debtId).stream()
                .filter(entry -> entry.getScheduleItem() != null)
                .collect(Collectors.toMap(entry -> entry.getScheduleItem().getId(),
                        entry -> entry.getTransaction().getId()));
        return scheduleRepository.findByDebtIdOrderByDueDateAscPositionAsc(debtId).stream()
                .map(item -> new LoanScheduleItemDto(item.getId(), item.getPosition(), item.getDueDate(),
                        item.getPrincipalAmount(), item.getInterestAmount(), item.getTotalAmount(),
                        item.getRemainingAfter(), item.isSkipped(),
                        paidBy.containsKey(item.getId()), paidBy.get(item.getId())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScheduleImportResult importSchedule(Long debtId, String parserId, MultipartFile file) {
        var debt = requireDebt(debtId);
        if (debt.getKind() != DebtKind.LOAN) {
            throw new ConflictException("Payment schedules are for LOAN debts only");
        }
        var parsed = parseSchedule(parserId, file);
        if (parsed.items() == null || parsed.items().isEmpty()) {
            throw new ConflictException("Schedule file contains no payment rows");
        }
        if (parsed.currency() != null && !parsed.currency().equalsIgnoreCase(debt.getCurrency())) {
            throw new ConflictException("Schedule currency %s does not match debt currency %s"
                    .formatted(parsed.currency(), debt.getCurrency()));
        }

        var minDate = parsed.items().stream()
                .map(org.zeveon.cashflows.statement.LoanScheduleDto.Item::dueDate)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        var replaced = scheduleRepository.deleteReplaceable(debtId, minDate);
        var survivorDates = scheduleRepository.findByDebtIdOrderByDueDateAscPositionAsc(debtId).stream()
                .map(LoanScheduleItem::getDueDate)
                .collect(Collectors.toSet());
        var inserted = 0;
        var keptPaid = 0;
        for (var item : parsed.items()) {
            if (survivorDates.contains(item.dueDate())) {
                keptPaid++;
                continue;
            }
            scheduleRepository.save(LoanScheduleItem.builder()
                    .debt(debt)
                    .position(item.position())
                    .dueDate(item.dueDate())
                    .principalAmount(item.principal())
                    .interestAmount(item.interest())
                    .totalAmount(item.total())
                    .remainingAfter(item.remaining())
                    .build());
            inserted++;
        }
        return new ScheduleImportResult(inserted, replaced, keptPaid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DebtEntryDto confirmPayment(LoanConfirmRequest request) {
        var item = scheduleRepository.findById(request.scheduleItemId())
                .orElseThrow(() -> NotFoundException.of("Schedule item", request.scheduleItemId()));
        var debt = item.getDebt();
        if (debt.isClosed()) {
            throw new ConflictException("Debt is closed");
        }
        requireLinkableItem(debt, item.getId());

        var accountId = request.accountId() != null ? request.accountId()
                : debt.getAccount() != null ? debt.getAccount().getId() : null;
        if (accountId == null) {
            throw new IllegalArgumentException(
                    "Debt has no payment account — pass accountId explicitly");
        }
        var account = accountRepository.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("Account", accountId));

        var amount = request.amount() != null ? request.amount() : item.getTotalAmount();
        var txnPrincipal = request.txnPrincipal() != null ? request.txnPrincipal()
                : item.getPrincipalAmount();
        if (txnPrincipal.compareTo(amount) > 0) {
            throw new IllegalArgumentException("Principal part exceeds the payment amount");
        }

        var transaction = transactionRepository.save(Transaction.builder()
                .type(expectedType(debt.getDirection(), DebtEntryRole.REPAYMENT))
                .txnDate(request.date() != null ? request.date() : item.getDueDate())
                .account(account)
                .amount(amount)
                .category(debt.getInterestCategory())
                .description("%s · payment #%d".formatted(debt.getName(), item.getPosition()))
                .source(TransactionSource.MANUAL)
                .build());
        return toDto(createEntry(debt, transaction, DebtEntryRole.REPAYMENT,
                txnPrincipal, item.getPrincipalAmount(), item));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setScheduleItemSkipped(Long scheduleItemId, boolean skipped) {
        var item = scheduleRepository.findById(scheduleItemId)
                .orElseThrow(() -> NotFoundException.of("Schedule item", scheduleItemId));
        if (skipped && entryRepository.findByScheduleItemId(scheduleItemId).isPresent()) {
            throw new ConflictException("Payment is already confirmed");
        }
        item.setSkipped(skipped);
    }

    // --- helpers ---

    /**
     * Which transaction type settles this movement: borrowing arrives as INCOME
     * and is repaid by EXPENSE; lending mirrors it.
     */
    public static TransactionType expectedType(DebtDirection direction, DebtEntryRole role) {
        var borrowingIssue = direction == DebtDirection.I_OWE && role == DebtEntryRole.ISSUE;
        var lendingRepayment = direction == DebtDirection.OWED_TO_ME && role == DebtEntryRole.REPAYMENT;
        return borrowingIssue || lendingRepayment ? TransactionType.INCOME : TransactionType.EXPENSE;
    }

    private DebtEntry createEntry(Debt debt, Transaction transaction, DebtEntryRole role,
                                  BigDecimal txnPrincipal, BigDecimal debtPrincipal,
                                  LoanScheduleItem item) {
        if (transaction.getType() == TransactionType.TRANSFER) {
            throw new ConflictException("Transfers cannot be linked to a debt");
        }
        var expected = expectedType(debt.getDirection(), role);
        if (transaction.getType() != expected) {
            throw new ConflictException("A %s %s must be a%s %s transaction".formatted(
                    debt.getDirection() == DebtDirection.I_OWE ? "borrowing" : "lending",
                    role.name().toLowerCase(),
                    expected == TransactionType.INCOME ? "n" : "", expected));
        }
        entryRepository.findByTransactionId(transaction.getId()).ifPresent(existing -> {
            throw new ConflictException("Transaction is already linked to debt '%s'"
                    .formatted(existing.getDebt().getName()));
        });
        if (transaction.getTxnDate().isBefore(debt.getAnchorDate())) {
            throw new ConflictException("Transaction predates the debt anchor date %s"
                    .formatted(debt.getAnchorDate()));
        }
        if (txnPrincipal.compareTo(transaction.getAmount()) > 0) {
            throw new IllegalArgumentException("txnPrincipal exceeds the transaction amount");
        }
        return entryRepository.save(DebtEntry.builder()
                .debt(debt)
                .transaction(transaction)
                .role(role)
                .txnPrincipal(txnPrincipal)
                .debtPrincipal(debtPrincipal)
                .scheduleItem(item)
                .build());
    }

    private BigDecimal defaultTxnPrincipal(BigDecimal requested, Transaction transaction,
                                           Debt debt, LoanScheduleItem item) {
        if (requested != null) {
            return requested;
        }
        if (item == null) {
            return transaction.getAmount();
        }
        if (transaction.getAccount().getCurrency().equalsIgnoreCase(debt.getCurrency())) {
            return item.getPrincipalAmount().min(transaction.getAmount());
        }
        throw new IllegalArgumentException(
                "Transaction and debt currencies differ — pass txnPrincipal explicitly");
    }

    private BigDecimal defaultDebtPrincipal(BigDecimal requested, BigDecimal txnPrincipal,
                                            Transaction transaction, Debt debt, LoanScheduleItem item) {
        if (requested != null) {
            return requested;
        }
        if (item != null) {
            return item.getPrincipalAmount();
        }
        if (transaction.getAccount().getCurrency().equalsIgnoreCase(debt.getCurrency())) {
            return txnPrincipal;
        }
        throw new IllegalArgumentException(
                "Transaction and debt currencies differ — pass debtPrincipal explicitly");
    }

    private LoanScheduleItem requireLinkableItem(Debt debt, Long scheduleItemId) {
        var item = scheduleRepository.findById(scheduleItemId)
                .orElseThrow(() -> NotFoundException.of("Schedule item", scheduleItemId));
        if (!item.getDebt().getId().equals(debt.getId())) {
            throw new ConflictException("Schedule item belongs to another debt");
        }
        if (item.isSkipped()) {
            throw new ConflictException("Schedule item is skipped");
        }
        if (!item.getDueDate().isAfter(debt.getAnchorDate())) {
            throw new ConflictException(
                    "Payment #%d predates the debt's opening balance (%s) — it is already counted"
                            .formatted(item.getPosition(), debt.getAnchorDate()));
        }
        entryRepository.findByScheduleItemId(item.getId()).ifPresent(existing -> {
            throw new ConflictException("Payment #%d is already confirmed".formatted(item.getPosition()));
        });
        return item;
    }

    private void apply(Debt debt, DebtRequest request) {
        debt.setName(request.name());
        debt.setDirection(request.direction());
        debt.setKind(request.kind());
        debt.setCounterparty(counterpartyRepository.findById(request.counterpartyId())
                .orElseThrow(() -> NotFoundException.of("Counterparty", request.counterpartyId())));
        debt.setCurrency(request.currency().toUpperCase());
        debt.setOpeningPrincipal(request.openingPrincipal());
        debt.setAnchorDate(request.anchorDate());
        debt.setExpectedReturnDate(request.expectedReturnDate());
        debt.setAccount(request.accountId() == null ? null
                : accountRepository.findById(request.accountId())
                .orElseThrow(() -> NotFoundException.of("Account", request.accountId())));
        debt.setInterestCategory(resolveInterestCategory(request));
        debt.setNote(request.note());
    }

    private org.zeveon.cashflows.entity.Category resolveInterestCategory(DebtRequest request) {
        if (request.interestCategoryId() == null) {
            if (request.kind() == DebtKind.LOAN) {
                throw new IllegalArgumentException("A LOAN needs an interest category");
            }
            return null;
        }
        var category = categoryRepository.findById(request.interestCategoryId())
                .orElseThrow(() -> NotFoundException.of("Category", request.interestCategoryId()));
        if (category.isSystem()) {
            throw new ConflictException(
                    "Interest must use a regular category — system ones are excluded from stats");
        }
        var expected = expectedType(request.direction(), DebtEntryRole.REPAYMENT) == TransactionType.EXPENSE
                ? CategoryType.EXPENSE : CategoryType.INCOME;
        if (category.getType() != expected) {
            throw new ConflictException("Interest category '%s' is %s, payments are %s"
                    .formatted(category.getName(), category.getType(), expected));
        }
        return category;
    }

    private Debt requireDebt(Long id) {
        return debtRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Debt", id));
    }

    private Debt requireOpenDebt(Long id) {
        var debt = requireDebt(id);
        if (debt.isClosed()) {
            throw new ConflictException("Debt is closed");
        }
        return debt;
    }

    private Map<Long, Map<DebtEntryRole, BigDecimal>> principalSums() {
        Map<Long, Map<DebtEntryRole, BigDecimal>> sums = new HashMap<>();
        for (var sum : entryRepository.sumPrincipalsByDebt()) {
            sums.computeIfAbsent(sum.getDebtId(), id -> new HashMap<>())
                    .put(sum.getRole(), sum.getTotal());
        }
        return sums;
    }

    private static BigDecimal outstandingOf(Debt debt, Map<Long, Map<DebtEntryRole, BigDecimal>> sums) {
        var byRole = sums.getOrDefault(debt.getId(), Map.of());
        return DebtCalculator.outstanding(debt,
                byRole.get(DebtEntryRole.ISSUE), byRole.get(DebtEntryRole.REPAYMENT));
    }

    private DebtDto toDto(Debt debt, Map<Long, Map<DebtEntryRole, BigDecimal>> sums) {
        var byRole = sums.getOrDefault(debt.getId(), Map.of());
        var issued = byRole.get(DebtEntryRole.ISSUE);
        var repaid = byRole.getOrDefault(DebtEntryRole.REPAYMENT, BigDecimal.ZERO);
        LoanScheduleItem next = null;
        if (debt.getKind() == DebtKind.LOAN && !debt.isClosed()) {
            next = scheduleRepository.findPendingByDebt(debt.getId(), PageRequest.of(0, 1)).stream()
                    .findFirst()
                    .orElse(null);
        }
        var account = debt.getAccount();
        var interestCategory = debt.getInterestCategory();
        return new DebtDto(debt.getId(), debt.getName(), debt.getDirection(), debt.getKind(),
                debt.getCounterparty().getId(), debt.getCounterparty().getName(),
                debt.getCurrency(), debt.getOpeningPrincipal(), debt.getAnchorDate(),
                debt.getExpectedReturnDate(),
                account == null ? null : account.getId(),
                account == null ? null : account.getName(),
                account == null ? null : account.getCurrency(),
                interestCategory == null ? null : interestCategory.getId(),
                interestCategory == null ? null : interestCategory.getName(),
                debt.getNote(), debt.isClosed(),
                DebtCalculator.outstanding(debt, issued, repaid),
                DebtCalculator.totalPrincipal(debt, issued), repaid,
                next == null ? null : next.getDueDate(),
                next == null ? null : next.getTotalAmount());
    }

    private static DebtEntryDto toDto(DebtEntry entry) {
        var transaction = entry.getTransaction();
        return new DebtEntryDto(entry.getId(), entry.getDebt().getId(), entry.getRole(),
                entry.getTxnPrincipal(), entry.getDebtPrincipal(),
                entry.getScheduleItem() == null ? null : entry.getScheduleItem().getId(),
                transaction.getId(), transaction.getTxnDate(), transaction.getAmount(),
                transaction.getAccount().getCurrency(), transaction.getAccount().getName(),
                transaction.getDescription());
    }

    private org.zeveon.cashflows.statement.LoanScheduleDto parseSchedule(String parserId,
                                                                         MultipartFile file) {
        Path temp = null;
        try {
            var name = file.getOriginalFilename();
            var dot = name == null ? -1 : name.lastIndexOf('.');
            temp = Files.createTempFile("schedule-", dot >= 0 ? name.substring(dot) : ".dat");
            file.transferTo(temp);
            return scheduleParserService.parse(parserId, temp);
        } catch (IOException e) {
            throw new RuntimeException("Could not store the uploaded schedule: " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

}
