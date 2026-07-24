package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.repository.DebtEntryRepository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Debt principal is not spending (FR-13.3): statistics subtract each linked
 * transaction's {@code txnPrincipal} from its amount, so a pure repayment
 * vanishes from expense/income aggregates while the interest part of a loan
 * payment stays a real expense. The subtraction happens in the transaction's
 * own currency, before any FX conversion.
 *
 * @author Stanislav Vafin
 */
@Component
@RequiredArgsConstructor
public class DebtExclusionSupport {

    private final DebtEntryRepository entryRepository;

    /**
     * txnPrincipal by transaction id for the given set, one query.
     */
    public Map<Long, BigDecimal> principalByTxnId(Collection<Transaction> transactions) {
        var ids = transactions.stream().map(Transaction::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return entryRepository.findByTransactionIdIn(ids).stream()
                .collect(Collectors.toMap(entry -> entry.getTransaction().getId(),
                        entry -> entry.getTxnPrincipal()));
    }

    /**
     * Amount minus the debt principal portion; zero means "drop from stats".
     */
    public static BigDecimal effectiveAmount(Transaction transaction,
                                             Map<Long, BigDecimal> principals) {
        var principal = principals.get(transaction.getId());
        if (principal == null) {
            return transaction.getAmount();
        }
        var effective = transaction.getAmount().subtract(principal);
        return effective.signum() > 0 ? effective : BigDecimal.ZERO;
    }
}
