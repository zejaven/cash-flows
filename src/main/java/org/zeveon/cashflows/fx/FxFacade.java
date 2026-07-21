package org.zeveon.cashflows.fx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.zeveon.cashflows.fx.model.FxRates;
import org.zeveon.cashflows.model.Cache;

import java.time.Instant;
import java.util.Optional;

import static org.zeveon.cashflows.fx.ApiUtil.executeApiCall;

/**
 * Daily mid-market FX rates (open.er-api.com, USD base). Fail-soft: an unavailable
 * source yields an empty Optional (the UI then shows original currencies without
 * conversion), and failures are not cached — the next call retries. Successful
 * tables are cached until the daily {@code UpdateFxCacheJob} evict; the upstream
 * itself refreshes only once a day.
 *
 * @author Stanislav Vafin
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxFacade {

    private static final String BASE = "USD";

    private final ExchangeRateApi exchangeRateApi;

    // Spring unwraps the Optional here, so #result is the FxRates (or null for an empty
    // Optional): skip caching failures so the next call retries
    @Cacheable(value = Cache.FX_RATES, unless = "#result == null")
    public Optional<FxRates> getRates() {
        try {
            var response = executeApiCall(exchangeRateApi.getLatest(BASE));
            if (!response.isSuccess()) {
                log.warn("FX rates response is not usable (result={})", response.getResult());
                return Optional.empty();
            }
            return Optional.of(new FxRates(
                    response.getBaseCode(),
                    response.getRates(),
                    Instant.ofEpochSecond(response.getTimeLastUpdateUnix())));
        } catch (Exception e) {
            log.warn("Could not fetch FX rates: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
