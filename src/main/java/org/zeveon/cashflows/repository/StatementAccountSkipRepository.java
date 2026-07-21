package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.StatementAccountSkip;

import java.util.List;
import java.util.Optional;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface StatementAccountSkipRepository extends JpaRepository<StatementAccountSkip, Long> {

    List<StatementAccountSkip> findByBankId(Long bankId);

    Optional<StatementAccountSkip> findByBankIdAndAccountNumber(Long bankId, String accountNumber);
}
