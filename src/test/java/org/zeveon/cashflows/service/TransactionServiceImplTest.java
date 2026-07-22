package org.zeveon.cashflows.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.TransactionSource;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.model.dto.ConvertToTransferRequest;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.impl.TransactionServiceImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Stanislav Vafin
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private TransactionServiceImpl transactionService;

    private Account account(long id, String currency) {
        return Account.builder().id(id).name("acc-" + id).currency(currency).build();
    }

    private Transaction transaction(long id, TransactionType type, Account account,
                                    String amount, String importKey) {
        return Transaction.builder()
                .id(id)
                .type(type)
                .txnDate(DATE)
                .account(account)
                .amount(new BigDecimal(amount))
                .importKey(importKey)
                .source(TransactionSource.IMPORT)
                .build();
    }

    @Test
    @DisplayName("expense + counterpart income merge into one transfer keeping both import keys")
    void convertExpenseAbsorbingIncome() {
        var kzt = account(1, "KZT");
        var usd = account(2, "USD");
        var expense = transaction(10, TransactionType.EXPENSE, kzt, "100000", "kzt-row");
        var income = transaction(11, TransactionType.INCOME, usd, "190", "usd-row");
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(expense));
        when(transactionRepository.findById(11L)).thenReturn(Optional.of(income));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(kzt));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(usd));

        transactionService.convertToTransfer(10L, new ConvertToTransferRequest(
                DATE, 1L, new BigDecimal("100000"), 2L, new BigDecimal("190"), "salary exchange", 11L));

        assertThat(expense.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(expense.getCounterAccount()).isEqualTo(usd);
        assertThat(expense.getCounterAmount()).isEqualByComparingTo("190");
        assertThat(expense.getCategory()).isNull();
        assertThat(expense.getImportKey()).isEqualTo("kzt-row");
        assertThat(expense.getCounterImportKey()).isEqualTo("usd-row");
        // the delete must hit the DB before the converted row takes over the
        // counterpart's import key, or uq_transaction_import_key fires
        var order = inOrder(transactionRepository);
        order.verify(transactionRepository).delete(income);
        order.verify(transactionRepository).flush();
    }

    @Test
    @DisplayName("converted income is the receiving side: its key moves to counterImportKey")
    void convertIncomeWithoutCounterpart() {
        var kzt = account(1, "KZT");
        var usd = account(2, "USD");
        var income = transaction(11, TransactionType.INCOME, usd, "190", "usd-row");
        when(transactionRepository.findById(11L)).thenReturn(Optional.of(income));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(kzt));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(usd));

        transactionService.convertToTransfer(11L, new ConvertToTransferRequest(
                DATE, 1L, new BigDecimal("100000"), 2L, new BigDecimal("190"), null, null));

        assertThat(income.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(income.getAccount()).isEqualTo(kzt);
        assertThat(income.getImportKey()).isNull();
        assertThat(income.getCounterImportKey()).isEqualTo("usd-row");
    }

    @Test
    @DisplayName("a transfer cannot be converted again")
    void convertTransferForbidden() {
        var transfer = transaction(10, TransactionType.TRANSFER, account(1, "KZT"), "5", null);
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(transfer));
        assertThatThrownBy(() -> transactionService.convertToTransfer(10L, new ConvertToTransferRequest(
                DATE, 1L, BigDecimal.ONE, 2L, BigDecimal.ONE, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("counterpart of the same direction is rejected")
    void counterpartSameTypeRejected() {
        var kzt = account(1, "KZT");
        var expense = transaction(10, TransactionType.EXPENSE, kzt, "100", "a");
        var otherExpense = transaction(11, TransactionType.EXPENSE, account(2, "USD"), "100", "b");
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(expense));
        when(transactionRepository.findById(11L)).thenReturn(Optional.of(otherExpense));
        assertThatThrownBy(() -> transactionService.convertToTransfer(10L, new ConvertToTransferRequest(
                DATE, 1L, BigDecimal.ONE, 2L, BigDecimal.ONE, null, 11L)))
                .isInstanceOf(ConflictException.class);
        verify(transactionRepository, never()).delete(otherExpense);
    }

    @Test
    @DisplayName("counterpart on an account other than the receiving one is rejected")
    void counterpartWrongAccountRejected() {
        var kzt = account(1, "KZT");
        var expense = transaction(10, TransactionType.EXPENSE, kzt, "100", "a");
        var strayIncome = transaction(11, TransactionType.INCOME, account(3, "EUR"), "100", "b");
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(expense));
        when(transactionRepository.findById(11L)).thenReturn(Optional.of(strayIncome));
        assertThatThrownBy(() -> transactionService.convertToTransfer(10L, new ConvertToTransferRequest(
                DATE, 1L, BigDecimal.ONE, 2L, BigDecimal.ONE, null, 11L)))
                .isInstanceOf(ConflictException.class);
        verify(transactionRepository, never()).delete(strayIncome);
    }
}
