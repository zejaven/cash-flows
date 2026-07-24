package org.zeveon.cashflows.repository.spec;

import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.zeveon.cashflows.entity.DebtEntry;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.model.TransactionFilter;

import java.util.ArrayList;

/**
 * @author Stanislav Vafin
 */
public final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    public static Specification<Transaction> matching(TransactionFilter filter) {
        return matching(filter, false);
    }

    /**
     * {@code excludeSystemCategories} — used by aggregates (FR-12.3): Correction
     * transactions affect balances but must not distort expense statistics.
     * Uncategorized transactions (needs review) are NOT system and stay included.
     */
    public static Specification<Transaction> matching(TransactionFilter filter,
                                                      boolean excludeSystemCategories) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("txnDate"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("txnDate"), filter.to()));
            }
            if (filter.accountId() != null) {
                // A transfer belongs to both account lists (FR-2.4)
                predicates.add(cb.or(
                        cb.equal(root.get("account").get("id"), filter.accountId()),
                        cb.equal(root.join("counterAccount", JoinType.LEFT).get("id"), filter.accountId())));
            }
            if (filter.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), filter.categoryId()));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("type"), filter.type()));
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("description")),
                        "%" + filter.q().toLowerCase() + "%"));
            }
            if (Boolean.TRUE.equals(filter.needsReview())) {
                // A fully-principal debt movement (txnPrincipal = amount) carries no
                // spending to categorize — it must not scream "needs review" (FR-13.3)
                var fullPrincipal = query.subquery(Long.class);
                var entry = fullPrincipal.from(DebtEntry.class);
                fullPrincipal.select(cb.literal(1L)).where(cb.and(
                        cb.equal(entry.get("transaction"), root),
                        cb.equal(entry.get("txnPrincipal"), root.get("amount"))));
                predicates.add(cb.and(
                        cb.isNull(root.get("category")),
                        cb.notEqual(root.get("type"), TransactionType.TRANSFER),
                        cb.not(cb.exists(fullPrincipal))));
            }
            if (excludeSystemCategories) {
                var category = root.join("category", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.isNull(category.get("id")),
                        cb.isFalse(category.get("system"))));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
