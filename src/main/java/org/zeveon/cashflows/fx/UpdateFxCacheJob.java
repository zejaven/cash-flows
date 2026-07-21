package org.zeveon.cashflows.fx;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.zeveon.cashflows.model.Cache;
import org.zeveon.cashflows.service.FxService;

/**
 * The upstream refreshes around 00:00 UTC; evict at 00:30, re-warm and persist
 * the daily snapshot (FR-6.3). On startup, warm the cache and backfill today's
 * snapshot if missing.
 *
 * @author Stanislav Vafin
 */
@Component
@RequiredArgsConstructor
public class UpdateFxCacheJob {

    private final FxService fxService;

    @Scheduled(cron = "0 30 0 * * *", zone = "UTC")
    @CacheEvict(value = Cache.FX_RATES, allEntries = true)
    public void updateFxRatesCache() {
        fxService.snapshotToday();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        fxService.snapshotToday();
    }
}
