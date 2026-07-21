package org.zeveon.cashflows.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.zeveon.cashflows.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Balance reconciliation (FR-12.x): actual balances entered by the user close
 * the gap with computed ones via Correction transactions.
 *
 * @author Stanislav Vafin
 */
public final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    public record ReconciliationRow(Long accountId, String name, String currency,
                                    BigDecimal computedBalance) {
    }

    public record ReconciliationEntry(@NotNull Long accountId, @NotNull BigDecimal actualBalance) {
    }

    public record ReconciliationRequest(@NotNull LocalDate date,
                                        @NotNull @Valid List<ReconciliationEntry> entries) {
    }

    public record CorrectionDto(Long transactionId, Long accountId, String accountName,
                                String currency, TransactionType type, BigDecimal amount) {
    }

    public record HistoryEntry(LocalDate date, List<CorrectionDto> corrections) {
    }
}
