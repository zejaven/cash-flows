package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.AveragingProfile;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface AveragingProfileRepository extends JpaRepository<AveragingProfile, Long> {

    List<AveragingProfile> findByArchivedFalseOrderByIdAsc();
}
