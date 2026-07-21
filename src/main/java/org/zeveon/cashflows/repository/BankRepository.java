package org.zeveon.cashflows.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.zeveon.cashflows.entity.Bank;

import java.util.Optional;

/**
 * @author Stanislav Vafin
 */
@Repository
public interface BankRepository extends JpaRepository<Bank, Long> {

    Optional<Bank> findByName(String name);
}
