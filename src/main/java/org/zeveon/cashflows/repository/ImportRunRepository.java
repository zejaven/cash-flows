package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.ImportRun;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface ImportRunRepository extends JpaRepository<ImportRun, Long> {

    List<ImportRun> findAllByOrderByCreatedAtDesc();
}
