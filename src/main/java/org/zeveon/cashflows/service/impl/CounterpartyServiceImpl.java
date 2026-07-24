package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Counterparty;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.mapper.CounterpartyMapper;
import org.zeveon.cashflows.model.dto.CounterpartyDto;
import org.zeveon.cashflows.model.dto.CounterpartyRequest;
import org.zeveon.cashflows.repository.CounterpartyRepository;
import org.zeveon.cashflows.repository.DebtRepository;
import org.zeveon.cashflows.service.CounterpartyService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class CounterpartyServiceImpl implements CounterpartyService {

    private final CounterpartyRepository counterpartyRepository;
    private final DebtRepository debtRepository;

    @Override
    @Transactional(readOnly = true)
    public List<CounterpartyDto> findAll(boolean includeArchived) {
        return counterpartyRepository.findAll().stream()
                .filter(counterparty -> includeArchived || !counterparty.isArchived())
                .map(counterparty -> CounterpartyMapper.INSTANCE.toDto(counterparty,
                        debtRepository.countByCounterpartyId(counterparty.getId())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CounterpartyDto create(CounterpartyRequest request) {
        var counterparty = counterpartyRepository.save(Counterparty.builder()
                .name(request.name())
                .note(request.note())
                .archived(Boolean.TRUE.equals(request.archived()))
                .build());
        return CounterpartyMapper.INSTANCE.toDto(counterparty, 0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CounterpartyDto update(Long id, CounterpartyRequest request) {
        var counterparty = counterpartyRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Counterparty", id));
        counterparty.setName(request.name());
        counterparty.setNote(request.note());
        if (request.archived() != null) {
            counterparty.setArchived(request.archived());
        }
        return CounterpartyMapper.INSTANCE.toDto(counterparty, debtRepository.countByCounterpartyId(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var counterparty = counterpartyRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Counterparty", id));
        if (debtRepository.countByCounterpartyId(id) > 0) {
            throw new ConflictException("Counterparty has debts — delete or reassign them first");
        }
        counterpartyRepository.delete(counterparty);
    }
}
