package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.AveragingProfile;
import org.zeveon.cashflows.entity.Category;
import org.zeveon.cashflows.entity.Transaction;
import org.zeveon.cashflows.entity.enums.AveragingGranularity;
import org.zeveon.cashflows.entity.enums.CategoryFilterMode;
import org.zeveon.cashflows.entity.enums.TransactionType;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.model.TransactionFilter;
import org.zeveon.cashflows.model.dto.AveragingProfileDtos.*;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.AveragingProfileRepository;
import org.zeveon.cashflows.repository.CategoryRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.repository.spec.TransactionSpecifications;
import org.zeveon.cashflows.service.AveragingProfileService;
import org.zeveon.cashflows.service.FxService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Average-spend computation (FR-8.x): EXPENSE transactions across ALL accounts
 * over the period, category filter INCLUDE/EXCLUDE, system categories excluded
 * unconditionally, each amount converted into the profile account's currency at
 * its own-date rate. DAILY = total/days; MONTHLY = total/(days/30.4375) — a
 * deterministic divisor independent of month-boundary alignment.
 *
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class AveragingProfileServiceImpl implements AveragingProfileService {

    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.4375");

    private final AveragingProfileRepository profileRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final FxService fxService;

    @Override
    @Transactional(readOnly = true)
    public List<ProfileDto> findAll() {
        return profileRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProfileDto create(ProfileRequest request) {
        var profile = new AveragingProfile();
        apply(profile, request);
        return toDto(profileRepository.save(profile));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProfileDto update(Long id, ProfileRequest request) {
        var profile = profileRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Averaging profile", id));
        apply(profile, request);
        return toDto(profile);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        profileRepository.delete(profileRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Averaging profile", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public ComputedValue preview(PreviewRequest request) {
        var account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> NotFoundException.of("Account", request.accountId()));
        return compute(account, request.granularity(), request.periodFrom(), request.periodTo(),
                request.categoryFilterMode(),
                request.categoryIds() == null ? Set.of() : Set.copyOf(request.categoryIds()));
    }

    /** Used by the forecast as well. */
    @Transactional(readOnly = true)
    public ComputedValue computeFor(AveragingProfile profile) {
        return compute(profile.getAccount(), profile.getGranularity(),
                profile.getPeriodFrom(), profile.getPeriodTo(), profile.getCategoryFilterMode(),
                profile.getCategories().stream().map(Category::getId).collect(Collectors.toSet()));
    }

    public static BigDecimal effectiveValue(AveragingProfile profile, ComputedValue computed) {
        return profile.getManualOverride() != null ? profile.getManualOverride() : computed.value();
    }

    private ComputedValue compute(Account account, AveragingGranularity granularity,
                                  LocalDate from, LocalDate to,
                                  CategoryFilterMode mode, Set<Long> categoryIds) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("periodTo is before periodFrom");
        }
        // system categories excluded by the spec flag; transfers filtered below
        var transactions = transactionRepository.findAll(TransactionSpecifications.matching(
                        new TransactionFilter(from, to, null, null, TransactionType.EXPENSE, null, null),
                        true)).stream()
                .filter(transaction -> matchesFilter(transaction, mode, categoryIds))
                .toList();

        var total = BigDecimal.ZERO;
        var count = 0L;
        var approx = false;
        for (var transaction : transactions) {
            var from2 = transaction.getAccount().getCurrency();
            var converted = fxService.convertAtDate(transaction.getAmount(), from2,
                    account.getCurrency(), transaction.getTxnDate());
            if (converted.isEmpty()) {
                approx = true;
                continue;
            }
            if (!from2.equals(account.getCurrency())) {
                approx = true;
            }
            total = total.add(converted.get());
            count++;
        }

        var dayCount = ChronoUnit.DAYS.between(from, to) + 1;
        BigDecimal value;
        if (count == 0) {
            value = BigDecimal.ZERO;
        } else if (granularity == AveragingGranularity.DAILY) {
            value = total.divide(BigDecimal.valueOf(dayCount), 2, RoundingMode.HALF_UP);
        } else {
            var months = BigDecimal.valueOf(dayCount)
                    .divide(DAYS_PER_MONTH, 10, RoundingMode.HALF_UP);
            value = total.divide(months, 2, RoundingMode.HALF_UP);
        }
        return new ComputedValue(value, dayCount, count, total.setScale(2, RoundingMode.HALF_UP), approx);
    }

    private static boolean matchesFilter(Transaction transaction, CategoryFilterMode mode,
                                         Set<Long> categoryIds) {
        var categoryId = transaction.getCategory() == null ? null : transaction.getCategory().getId();
        if (mode == CategoryFilterMode.INCLUDE) {
            return categoryId != null && categoryIds.contains(categoryId);
        }
        // EXCLUDE: uncategorized spending still counts — it is real money out
        return categoryId == null || !categoryIds.contains(categoryId);
    }

    private void apply(AveragingProfile profile, ProfileRequest request) {
        if (request.periodTo().isBefore(request.periodFrom())) {
            throw new IllegalArgumentException("periodTo is before periodFrom");
        }
        profile.setName(request.name());
        profile.setAccount(accountRepository.findById(request.accountId())
                .orElseThrow(() -> NotFoundException.of("Account", request.accountId())));
        profile.setGranularity(request.granularity());
        profile.setPeriodFrom(request.periodFrom());
        profile.setPeriodTo(request.periodTo());
        profile.setCategoryFilterMode(request.categoryFilterMode());
        var categories = new LinkedHashSet<Category>();
        for (var categoryId : request.categoryIds() == null ? List.<Long>of() : request.categoryIds()) {
            categories.add(categoryRepository.findById(categoryId)
                    .orElseThrow(() -> NotFoundException.of("Category", categoryId)));
        }
        profile.setCategories(categories);
        profile.setManualOverride(request.manualOverride());
        if (request.archived() != null) {
            profile.setArchived(request.archived());
        }
    }

    private ProfileDto toDto(AveragingProfile profile) {
        var computed = computeFor(profile);
        return new ProfileDto(profile.getId(), profile.getName(),
                profile.getAccount().getId(), profile.getAccount().getName(),
                profile.getAccount().getCurrency(),
                profile.getGranularity(), profile.getPeriodFrom(), profile.getPeriodTo(),
                profile.getCategoryFilterMode(),
                profile.getCategories().stream().map(Category::getId).sorted().toList(),
                profile.getManualOverride(), profile.isArchived(),
                computed, effectiveValue(profile, computed));
    }
}
