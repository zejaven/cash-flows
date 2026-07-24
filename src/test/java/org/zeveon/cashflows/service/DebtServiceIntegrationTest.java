package org.zeveon.cashflows.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.zeveon.cashflows.entity.*;
import org.zeveon.cashflows.entity.enums.*;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.model.dto.DebtDtos.*;
import org.zeveon.cashflows.model.dto.RecurringDtos.QueueKind;
import org.zeveon.cashflows.recurring.RecurringAmountCalculator;
import org.zeveon.cashflows.repository.*;
import org.zeveon.cashflows.service.impl.DebtServiceImpl;
import org.zeveon.cashflows.service.impl.RecurringServiceImpl;
import org.zeveon.cashflows.statement.LoanScheduleDto;
import org.zeveon.cashflows.statement.LoanScheduleParserService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Debt flows end-to-end on the compose postgres (cash_flows_test): outstanding
 * arithmetic through record/link/unlink, loan payment confirmation from the
 * schedule with the queue integration, transaction-delete cascade reopening a
 * paid row, and schedule re-import that keeps paid history (FR-13).
 *
 * @author Stanislav Vafin
 */
@Tag("integration")
@DataJpaTest
@Import({DebtServiceImpl.class, RecurringServiceImpl.class, RecurringAmountCalculator.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5434/cash_flows_test",
        "spring.datasource.username=${DB_USERNAME:cashflows}",
        "spring.datasource.password=${DB_PASSWORD:cashflows-local}",
})
class DebtServiceIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 21);
    private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 1);

    @Autowired
    private DebtService debtService;

    @Autowired
    private RecurringService recurringService;

    @Autowired
    private DebtRepository debtRepository;

    @Autowired
    private DebtEntryRepository entryRepository;

    @Autowired
    private LoanScheduleItemRepository scheduleRepository;

    @Autowired
    private CounterpartyRepository counterpartyRepository;

    @Autowired
    private RecurringRuleRepository ruleRepository;

    @Autowired
    private RecurringOccurrenceRepository occurrenceRepository;

    @Autowired
    private PlannedTransactionRepository plannedRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private FxService fxService;

    @MockitoBean
    private LoanScheduleParserService scheduleParserService;

    private Account account;
    private Counterparty counterparty;
    private Category interest;

    @BeforeEach
    void setUp() {
        entryRepository.deleteAll();
        scheduleRepository.deleteAll();
        debtRepository.deleteAll();
        counterpartyRepository.deleteAll();
        occurrenceRepository.deleteAll();
        ruleRepository.deleteAll();
        plannedRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        account = accountRepository.save(Account.builder()
                .name("RSHB Card RUB").currency("RUB").type(AccountType.BANK)
                .startBalance(BigDecimal.ZERO).startDate(ANCHOR)
                .archived(false).build());
        counterparty = counterpartyRepository.save(Counterparty.builder().name("РСХБ").build());
        interest = categoryRepository.findByNameAndType("rent", CategoryType.EXPENSE).orElseThrow();
    }

    private DebtDto personalDebt() {
        return debtService.create(new DebtRequest("Friend loan", DebtDirection.OWED_TO_ME,
                DebtKind.PERSONAL, counterparty.getId(), "RUB", BigDecimal.ZERO, ANCHOR,
                null, null, null, null));
    }

    private DebtDto loanDebt() {
        return debtService.create(new DebtRequest("Ипотека", DebtDirection.I_OWE,
                DebtKind.LOAN, counterparty.getId(), "RUB", new BigDecimal("2126259.84"), ANCHOR,
                null, account.getId(), interest.getId(), null));
    }

    private LoanScheduleItem scheduleRow(Long debtId, int position, LocalDate dueDate,
                                         String principal, String interestAmount) {
        return scheduleRepository.save(LoanScheduleItem.builder()
                .debt(debtRepository.findById(debtId).orElseThrow())
                .position(position)
                .dueDate(dueDate)
                .principalAmount(new BigDecimal(principal))
                .interestAmount(new BigDecimal(interestAmount))
                .totalAmount(new BigDecimal(principal).add(new BigDecimal(interestAmount)))
                .remainingAfter(null)
                .build());
    }

    @Test
    @DisplayName("record issue + link imported repayment move the outstanding; unlink restores it")
    void outstandingFlow() {
        var debt = personalDebt();

        debtService.record(debt.id(), new DebtRecordRequest(DebtEntryRole.ISSUE,
                LocalDate.of(2026, 2, 1), account.getId(), new BigDecimal("10000"),
                null, null, "lent to a friend"));
        assertThat(current(debt.id()).outstanding()).isEqualByComparingTo("10000");

        var imported = transactionRepository.save(Transaction.builder()
                .type(TransactionType.INCOME).txnDate(LocalDate.of(2026, 5, 20))
                .account(account).amount(new BigDecimal("4000"))
                .source(TransactionSource.IMPORT).importKey("row-1").build());
        var entry = debtService.link(debt.id(), new DebtLinkRequest(imported.getId(),
                DebtEntryRole.REPAYMENT, null, null, null));
        assertThat(current(debt.id()).outstanding()).isEqualByComparingTo("6000");
        assertThat(current(debt.id()).totalRepaid()).isEqualByComparingTo("4000");

        assertThatThrownBy(() -> debtService.delete(debt.id()))
                .isInstanceOf(ConflictException.class);

        debtService.unlink(entry.id());
        assertThat(current(debt.id()).outstanding()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("due schedule row shows in the queue; confirm creates txn + entry; deleting the txn reopens the row")
    void loanConfirmAndCascadeReopen() {
        var debt = loanDebt();
        var row1 = scheduleRow(debt.id(), 1, LocalDate.of(2026, 6, 29), "11546.27", "5476.96");
        scheduleRow(debt.id(), 2, LocalDate.of(2026, 8, 28), "11605.63", "5417.60");

        var queue = recurringService.queue(TODAY);
        assertThat(queue).hasSize(1);
        assertThat(queue.getFirst().kind()).isEqualTo(QueueKind.LOAN);
        assertThat(queue.getFirst().scheduleItemId()).isEqualTo(row1.getId());
        assertThat(queue.getFirst().suggestedAmount()).isEqualByComparingTo("17023.23");

        var entry = debtService.confirmPayment(new LoanConfirmRequest(row1.getId(),
                null, null, null, null));
        var transaction = transactionRepository.findById(entry.transactionId()).orElseThrow();
        assertThat(transaction.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(transaction.getAmount()).isEqualByComparingTo("17023.23");
        assertThat(transaction.getCategory().getId()).isEqualTo(interest.getId());
        assertThat(transaction.getTxnDate()).isEqualTo(LocalDate.of(2026, 6, 29));

        assertThat(recurringService.queue(TODAY)).isEmpty();
        assertThat(debtService.schedule(debt.id()))
                .filteredOn(LoanScheduleItemDto::paid).hasSize(1);
        // the paid principal now counts as repaid
        assertThat(current(debt.id()).totalRepaid()).isEqualByComparingTo("11546.27");

        // deleting the transaction cascades the entry away and reopens the row;
        // clear first so the session holds no entry referencing the doomed txn
        entityManager.flush();
        entityManager.clear();
        transactionRepository.deleteById(entry.transactionId());
        entityManager.flush();
        entityManager.clear();
        assertThat(debtService.schedule(debt.id())).noneMatch(LoanScheduleItemDto::paid);
        assertThat(recurringService.queue(TODAY)).hasSize(1);
    }

    @Test
    @DisplayName("interest-only grace payment confirms with zero principal and leaves the outstanding intact")
    void gracePaymentZeroPrincipal() {
        var debt = loanDebt();
        var grace = scheduleRow(debt.id(), 1, LocalDate.of(2026, 6, 22), "0", "36990");

        var entry = debtService.confirmPayment(new LoanConfirmRequest(grace.getId(),
                null, null, null, null));

        assertThat(entry.txnPrincipal()).isEqualByComparingTo("0");
        var transaction = transactionRepository.findById(entry.transactionId()).orElseThrow();
        assertThat(transaction.getAmount()).isEqualByComparingTo("36990");
        assertThat(debtService.schedule(debt.id())).allMatch(LoanScheduleItemDto::paid);
        // nothing repaid — the whole payment is interest
        assertThat(current(debt.id()).outstanding())
                .isEqualByComparingTo(debt.openingPrincipal());
    }

    @Test
    @DisplayName("rows due on or before the anchor date are covered by the opening balance")
    void historyRowsCoveredByOpeningBalance() {
        var debt = loanDebt();   // anchor 2026-01-01, opening = current outstanding
        var covered = scheduleRow(debt.id(), 1, LocalDate.of(2025, 12, 28), "11551.46", "5471.77");
        var due = scheduleRow(debt.id(), 2, LocalDate.of(2026, 6, 29), "11546.27", "5476.96");

        // the queue and "next payment" skip the pre-anchor history
        var queue = recurringService.queue(TODAY);
        assertThat(queue).hasSize(1);
        assertThat(queue.getFirst().scheduleItemId()).isEqualTo(due.getId());
        assertThat(current(debt.id()).nextPaymentDate()).isEqualTo(LocalDate.of(2026, 6, 29));

        // confirming history would double-count the opening balance
        assertThatThrownBy(() -> debtService.confirmPayment(new LoanConfirmRequest(
                covered.getId(), null, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already counted");
    }

    @Test
    @DisplayName("re-import replaces future unpaid rows, keeps the paid ones, rejects a foreign currency")
    void scheduleReimport() {
        var debt = loanDebt();
        var paid = scheduleRow(debt.id(), 1, LocalDate.of(2026, 6, 29), "11546.27", "5476.96");
        scheduleRow(debt.id(), 2, LocalDate.of(2026, 7, 28), "11750.48", "5272.75");
        scheduleRow(debt.id(), 3, LocalDate.of(2026, 8, 28), "11605.63", "5417.60");
        debtService.confirmPayment(new LoanConfirmRequest(paid.getId(), null, null, null, null));

        // bank reissued after an early repayment: overlapping window incl. the paid date
        var reissued = new LoanScheduleDto("PaymentSchedule.pdf", "RUB", List.of(
                new LoanScheduleDto.Item(1, LocalDate.of(2026, 6, 29),
                        new BigDecimal("11546.27"), new BigDecimal("5476.96"),
                        new BigDecimal("17023.23"), null),
                new LoanScheduleDto.Item(2, LocalDate.of(2026, 7, 28),
                        new BigDecimal("50000"), new BigDecimal("5000"),
                        new BigDecimal("55000"), null),
                new LoanScheduleDto.Item(3, LocalDate.of(2026, 8, 30),
                        new BigDecimal("11000"), new BigDecimal("5300"),
                        new BigDecimal("16300"), null)));
        when(scheduleParserService.parse(eq("rshb"), any())).thenReturn(reissued);

        var file = new MockMultipartFile("file", "PaymentSchedule.pdf",
                "application/pdf", new byte[]{1});
        var result = debtService.importSchedule(debt.id(), "rshb", file);

        assertThat(result.replaced()).isEqualTo(2);   // unpaid jul + aug rows
        assertThat(result.keptPaid()).isEqualTo(1);   // reissued jun row collides with paid
        assertThat(result.inserted()).isEqualTo(2);

        var schedule = debtService.schedule(debt.id());
        assertThat(schedule).hasSize(3);
        assertThat(schedule).filteredOn(LoanScheduleItemDto::paid).hasSize(1);
        assertThat(schedule)
                .filteredOn(item -> item.dueDate().equals(LocalDate.of(2026, 7, 28)))
                .first()
                .satisfies(item -> assertThat(item.totalAmount()).isEqualByComparingTo("55000"));

        when(scheduleParserService.parse(eq("rshb"), any())).thenReturn(
                new LoanScheduleDto("x.pdf", "USD", reissued.items()));
        assertThatThrownBy(() -> debtService.importSchedule(debt.id(), "rshb", file))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("currency");
    }

    private DebtDto current(Long id) {
        return debtService.findAll(true).stream()
                .filter(debt -> debt.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
