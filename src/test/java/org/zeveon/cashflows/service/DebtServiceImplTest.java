package org.zeveon.cashflows.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Counterparty;
import org.zeveon.cashflows.entity.Debt;
import org.zeveon.cashflows.entity.DebtEntry;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.DebtDirection;
import org.zeveon.cashflows.entity.enums.DebtEntryRole;
import org.zeveon.cashflows.entity.enums.DebtKind;
import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.model.dto.DebtDtos.DebtLinkRequest;
import org.zeveon.cashflows.model.dto.DebtDtos.DebtRequest;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.CounterpartyRepository;
import org.zeveon.cashflows.repository.DebtEntryRepository;
import org.zeveon.cashflows.repository.DebtRepository;
import org.zeveon.cashflows.repository.LoanScheduleItemRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.impl.DebtServiceImpl;
import org.zeveon.cashflows.statement.LoanScheduleParserService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Stanislav Vafin
 */
@ExtendWith(MockitoExtension.class)
class DebtServiceImplTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 1, 1);

    @Mock
    private DebtRepository debtRepository;

    @Mock
    private DebtEntryRepository entryRepository;

    @Mock
    private LoanScheduleItemRepository scheduleRepository;

    @Mock
    private CounterpartyRepository counterpartyRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LoanScheduleParserService scheduleParserService;

    @Mock
    private FxService fxService;

    @InjectMocks
    private DebtServiceImpl debtService;

    private Account account(long id, String currency) {
        return Account.builder().id(id).name("acc-" + id).currency(currency).build();
    }

    private Debt debt(long id, DebtDirection direction) {
        return Debt.builder()
                .id(id)
                .name("debt-" + id)
                .direction(direction)
                .kind(DebtKind.PERSONAL)
                .counterparty(Counterparty.builder().id(1L).name("Friend").build())
                .currency("RUB")
                .openingPrincipal(BigDecimal.ZERO)
                .anchorDate(ANCHOR)
                .build();
    }

    private Transaction transaction(long id, TransactionType type, String amount) {
        return Transaction.builder()
                .id(id)
                .type(type)
                .txnDate(ANCHOR.plusDays(10))
                .account(account(1, "RUB"))
                .amount(new BigDecimal(amount))
                .source(TransactionSource.IMPORT)
                .build();
    }

    @Test
    @DisplayName("direction × role fixes the transaction type: borrowing arrives as INCOME")
    void expectedTypeMatrix() {
        assertThat(DebtServiceImpl.expectedType(DebtDirection.I_OWE, DebtEntryRole.ISSUE))
                .isEqualTo(TransactionType.INCOME);
        assertThat(DebtServiceImpl.expectedType(DebtDirection.I_OWE, DebtEntryRole.REPAYMENT))
                .isEqualTo(TransactionType.EXPENSE);
        assertThat(DebtServiceImpl.expectedType(DebtDirection.OWED_TO_ME, DebtEntryRole.ISSUE))
                .isEqualTo(TransactionType.EXPENSE);
        assertThat(DebtServiceImpl.expectedType(DebtDirection.OWED_TO_ME, DebtEntryRole.REPAYMENT))
                .isEqualTo(TransactionType.INCOME);
    }

    @Test
    @DisplayName("linking a repayment of a lent debt records the INCOME and full principal")
    void linkRepayment() {
        var debt = debt(5, DebtDirection.OWED_TO_ME);
        var income = transaction(10, TransactionType.INCOME, "500");
        when(debtRepository.findById(5L)).thenReturn(Optional.of(debt));
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(income));
        when(entryRepository.findByTransactionId(10L)).thenReturn(Optional.empty());
        when(entryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var dto = debtService.link(5L, new DebtLinkRequest(10L, DebtEntryRole.REPAYMENT,
                null, null, null));

        assertThat(dto.role()).isEqualTo(DebtEntryRole.REPAYMENT);
        assertThat(dto.txnPrincipal()).isEqualByComparingTo("500");
        assertThat(dto.debtPrincipal()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("wrong transaction type for the movement is rejected")
    void linkWrongType() {
        var debt = debt(5, DebtDirection.OWED_TO_ME);
        var expense = transaction(10, TransactionType.EXPENSE, "500");
        when(debtRepository.findById(5L)).thenReturn(Optional.of(debt));
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(expense));

        assertThatThrownBy(() -> debtService.link(5L, new DebtLinkRequest(10L,
                DebtEntryRole.REPAYMENT, null, null, null)))
                .isInstanceOf(ConflictException.class);
        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("a transaction already linked to a debt cannot be linked twice")
    void linkTwiceRejected() {
        var debt = debt(5, DebtDirection.I_OWE);
        var expense = transaction(10, TransactionType.EXPENSE, "500");
        when(debtRepository.findById(5L)).thenReturn(Optional.of(debt));
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(expense));
        when(entryRepository.findByTransactionId(10L)).thenReturn(Optional.of(
                DebtEntry.builder().debt(debt).build()));

        assertThatThrownBy(() -> debtService.link(5L, new DebtLinkRequest(10L,
                DebtEntryRole.REPAYMENT, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already linked");
    }

    @Test
    @DisplayName("txnPrincipal above the transaction amount is rejected")
    void principalAboveAmount() {
        var debt = debt(5, DebtDirection.I_OWE);
        var expense = transaction(10, TransactionType.EXPENSE, "500");
        when(debtRepository.findById(5L)).thenReturn(Optional.of(debt));
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(expense));
        when(entryRepository.findByTransactionId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> debtService.link(5L, new DebtLinkRequest(10L,
                DebtEntryRole.REPAYMENT, null, new BigDecimal("600"), new BigDecimal("600"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("transactions before the anchor date are rejected — the opening balance covers them")
    void linkBeforeAnchor() {
        var debt = debt(5, DebtDirection.I_OWE);
        var expense = transaction(10, TransactionType.EXPENSE, "500");
        expense.setTxnDate(ANCHOR.minusDays(1));
        when(debtRepository.findById(5L)).thenReturn(Optional.of(debt));
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(expense));
        when(entryRepository.findByTransactionId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> debtService.link(5L, new DebtLinkRequest(10L,
                DebtEntryRole.REPAYMENT, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("anchor");
    }

    @Test
    @DisplayName("cross-currency link requires explicit principals")
    void crossCurrencyNeedsExplicitAmounts() {
        var debt = debt(5, DebtDirection.I_OWE);
        var expense = Transaction.builder()
                .id(10L)
                .type(TransactionType.EXPENSE)
                .txnDate(ANCHOR.plusDays(3))
                .account(account(2, "USD"))
                .amount(new BigDecimal("100"))
                .source(TransactionSource.MANUAL)
                .build();
        when(debtRepository.findById(5L)).thenReturn(Optional.of(debt));
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(expense));

        assertThatThrownBy(() -> debtService.link(5L, new DebtLinkRequest(10L,
                DebtEntryRole.REPAYMENT, null, new BigDecimal("100"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("debtPrincipal");
    }

    @Test
    @DisplayName("a debt with linked transactions cannot be deleted")
    void deleteWithEntries() {
        var debt = debt(5, DebtDirection.I_OWE);
        when(debtRepository.findById(5L)).thenReturn(Optional.of(debt));
        when(entryRepository.existsByDebtId(5L)).thenReturn(true);

        assertThatThrownBy(() -> debtService.delete(5L)).isInstanceOf(ConflictException.class);
        verify(debtRepository, never()).delete(debt);
    }

    @Test
    @DisplayName("a LOAN needs an interest category")
    void loanNeedsInterestCategory() {
        when(counterpartyRepository.findById(1L))
                .thenReturn(Optional.of(Counterparty.builder().id(1L).name("RSHB").build()));
        var request = new DebtRequest("Mortgage", DebtDirection.I_OWE, DebtKind.LOAN,
                1L, "RUB", new BigDecimal("2126259.84"), ANCHOR, null, null, null, null);

        assertThatThrownBy(() -> debtService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interest category");
    }
}
