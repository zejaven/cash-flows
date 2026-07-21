package org.zeveon.cashflows.model;

/**
 * Cache names. TTL is managed by cron evicts in UpdateFxCacheJob, not by the cache itself.
 *
 * @author Stanislav Vafin
 */
public final class Cache {

    public static final String FX_RATES = "fxRates";

    private Cache() {
    }
}
