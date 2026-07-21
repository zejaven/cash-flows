package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.CategoryRule;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Long> {

    List<CategoryRule> findByArchivedFalseOrderByIdAsc();
}
