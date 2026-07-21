package org.zeveon.cashflows.repository.spec;

import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
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
                predicates.add(cb.and(
                        cb.isNull(root.get("category")),
                        cb.notEqual(root.get("type"), TransactionType.TRANSFER)));
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
