package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.FxRateSnapshot;
import org.zeveon.cashflows.fx.FxFacade;
import org.zeveon.cashflows.fx.model.FxRates;
import org.zeveon.cashflows.repository.FxRateSnapshotRepository;
import org.zeveon.cashflows.service.FxService;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * @author Stanislav Vafin
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxServiceImpl implements FxService {

    private final FxFacade fxFacade;
    private final FxRateSnapshotRepository snapshotRepository;

    @Override
    public Optional<FxRates> currentRates() {
        return fxFacade.getRates();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Map<String, Double>> ratesForDate(LocalDate date) {
        return snapshotRepository.findTopBySnapshotDateLessThanEqualOrderBySnapshotDateDesc(date)
                .map(FxRateSnapshot::getRates)
                .or(() -> fxFacade.getRates().map(FxRates::rates));
    }

    @Override
    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to, Map<String, Double> rates) {
        var fromRate = rates.get(peg(from));
        var toRate = rates.get(peg(to));
        if (fromRate == null || toRate == null || fromRate == 0) {
            return Optional.empty();
        }
        return Optional.of(amount.multiply(
                BigDecimal.valueOf(toRate).divide(BigDecimal.valueOf(fromRate), MathContext.DECIMAL64)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void snapshotToday() {
        // Snapshots are dated by UTC day; the "nearest snapshot <= date" rule makes
        // the one-day skew against the user's timezone harmless.
        var today = LocalDate.now(ZoneOffset.UTC);
        if (snapshotRepository.existsBySnapshotDate(today)) {
            return;
        }
        fxFacade.getRates().ifPresent(rates -> {
            snapshotRepository.save(FxRateSnapshot.builder()
                    .snapshotDate(today)
                    .baseCurrency(rates.base())
                    .rates(rates.rates())
                    .fetchedAt(rates.updatedAt())
                    .build());
            log.info("Stored FX snapshot for {} ({} currencies)", today, rates.rates().size());
        });
    }

    private static String peg(String currency) {
        return "USDT".equals(currency) ? "USD" : currency;
    }
}
