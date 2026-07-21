package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.enums.CategoryType;

import java.util.Optional;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByNameAndType(String name, CategoryType type);
}
