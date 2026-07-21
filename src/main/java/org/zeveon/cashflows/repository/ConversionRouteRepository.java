package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.ConversionRoute;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface ConversionRouteRepository extends JpaRepository<ConversionRoute, Long> {

    List<ConversionRoute> findByArchivedFalseOrderByIdAsc();
}
