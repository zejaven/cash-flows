package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Bank;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.mapper.BankMapper;
import org.zeveon.cashflows.model.dto.BankDto;
import org.zeveon.cashflows.model.dto.BankRequest;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.BankRepository;
import org.zeveon.cashflows.service.BankService;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class BankServiceImpl implements BankService {

    private final BankRepository bankRepository;
    private final AccountRepository accountRepository;

    @Override
    @Transactional(readOnly = true)
    public List<BankDto> findAll() {
        return bankRepository.findAll().stream()
                .map(bank -> BankMapper.INSTANCE.toDto(bank, accountRepository.countByBankId(bank.getId())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BankDto create(BankRequest request) {
        var bank = bankRepository.save(Bank.builder()
                .name(request.name())
                .parserId(request.parserId())
                .build());
        return BankMapper.INSTANCE.toDto(bank, 0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BankDto update(Long id, BankRequest request) {
        var bank = bankRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Bank", id));
        bank.setName(request.name());
        bank.setParserId(request.parserId());
        return BankMapper.INSTANCE.toDto(bank, accountRepository.countByBankId(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var bank = bankRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Bank", id));
        if (accountRepository.countByBankId(id) > 0) {
            throw new ConflictException("Bank has linked accounts — unlink them first");
        }
        bankRepository.delete(bank);
    }
}
