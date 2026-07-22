package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.GeneratorSettings;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface GeneratorSettingsRepository extends JpaRepository<GeneratorSettings, Long> {
}
