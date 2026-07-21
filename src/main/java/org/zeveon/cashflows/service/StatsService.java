package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.StatsDtos.CategoriesResponse;
import org.zeveon.cashflows.model.dto.StatsDtos.FactVsForecast;
import org.zeveon.cashflows.model.dto.StatsDtos.Granularity;

import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
public interface StatsService {

    /**
     * Expense totals per category per period bucket (FR-10.1), converted with
     * each transaction's own-date rate table. Transfers excluded; system
     * categories hidden unless {@code includeSystem} (FR-12.3).
     */
    CategoriesResponse categories(LocalDate from, LocalDate to, Granularity granularity,
                                  String currency, boolean includeSystem);

    /**
     * Actual expenses vs what the averaging profiles predicted for the period (FR-10.4).
     */
    FactVsForecast factVsForecast(LocalDate from, LocalDate to, String currency);
}
