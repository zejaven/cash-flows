package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.FxRateSnapshot;

import java.time.LocalDate;
import java.util.Optional;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface FxRateSnapshotRepository extends JpaRepository<FxRateSnapshot, Long> {

    boolean existsBySnapshotDate(LocalDate snapshotDate);

    /**
     * Rate table effective on the given date (FR-6.3): the latest snapshot
     * taken on or before it.
     */
    Optional<FxRateSnapshot> findTopBySnapshotDateLessThanEqualOrderBySnapshotDateDesc(LocalDate date);
}
