package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.dto.ForecastDtos.ForecastResponse;

import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
public interface ForecastService {

    /**
     * On-the-fly balance projection (FR-9.1, FR-9.3): current balances plus
     * unconfirmed recurring occurrences (overdue ones land on today), pending
     * planned transactions, averaging profiles and future-dated real transactions.
     * All dates come from the client.
     */
    ForecastResponse forecast(LocalDate today, int horizonMonths, String currency,
                              LocalDate historyFrom);
}
