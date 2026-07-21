package org.zeveon.cashflows.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Statement import contract (FR-4.x): two-phase preview -> confirm.
 *
 * @author Stanislav Vafin
 */
public final class ImportDtos {

    private ImportDtos() {
    }

    public enum MappingAction {MAP, CREATE, SKIP}

    public enum SuggestionKind {AUTO, SKIP, UNMAPPED}

    public record AccountSuggestion(SuggestionKind kind, Long accountId, String accountName) {
    }

    public record PreviewAccount(String accountNumber, String currency, int txnCount,
                                 BigDecimal balanceStart, BigDecimal balanceEnd,
                                 AccountSuggestion suggestion) {
    }

    public record CashCurrency(String currency, int withdrawalCount, Long suggestedAccountId,
                               String suggestedAccountName) {
    }

    public record ImportPreview(UUID token, String fileName, LocalDate periodFrom, LocalDate periodTo,
                                int totalRows, List<PreviewAccount> accounts,
                                List<CashCurrency> cashCurrencies) {
    }

    public record NewAccountSpec(@NotBlank String name, BigDecimal startBalance, LocalDate startDate) {
    }

    public record AccountDecision(@NotBlank String accountNumber, @NotNull MappingAction action,
                                  Long accountId, @Valid NewAccountSpec newAccount) {
    }

    public record CashDecision(@NotBlank String currency, Long accountId, boolean createNew) {
    }

    public record ConfirmRequest(@NotNull UUID token, @NotNull @Valid List<AccountDecision> accounts,
                                 @Valid List<CashDecision> cash) {
    }

    public record ReportAccount(Long accountId, String name, String currency,
                                BigDecimal balanceBefore, BigDecimal balanceAfter) {
    }

    public record ImportReport(long importRunId, int created, int duplicates, int needsReview,
                               int transfers, List<String> warnings, List<ReportAccount> accounts) {
    }

    public record ImportRunDto(Long id, String bankName, String fileName, LocalDate periodFrom,
                               LocalDate periodTo, int created, int duplicates, int needsReview,
                               int transfers, Instant createdAt) {
    }
}
