package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zeveon.cashflows.entity.Account;
import org.zeveon.cashflows.entity.Bank;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.mapper.AccountMapper;
import org.zeveon.cashflows.model.dto.AccountDto;
import org.zeveon.cashflows.model.dto.AccountRequest;
import org.zeveon.cashflows.repository.AccountRepository;
import org.zeveon.cashflows.repository.BankRepository;
import org.zeveon.cashflows.repository.TransactionRepository;
import org.zeveon.cashflows.service.AccountService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;
    private final BankRepository bankRepository;
    private final TransactionRepository transactionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AccountDto> findAll(boolean includeArchived) {
        Map<Long, BigDecimal> balances = accountRepository.findAllBalances().stream()
                .collect(Collectors.toMap(AccountRepository.AccountBalance::getId,
                        AccountRepository.AccountBalance::getBalance));
        return accountRepository.findAll().stream()
                .filter(account -> includeArchived || !account.isArchived())
                .map(account -> AccountMapper.INSTANCE.toDto(account,
                        balances.getOrDefault(account.getId(), account.getStartBalance())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountDto create(AccountRequest request) {
        var account = accountRepository.save(Account.builder()
                .name(request.name())
                .currency(request.currency())
                .type(request.type())
                .bank(resolveBank(request.bankId()))
                .accountNumber(request.accountNumber())
                .startBalance(request.startBalance())
                .startDate(request.startDate())
                .archived(Boolean.TRUE.equals(request.archived()))
                .build());
        return AccountMapper.INSTANCE.toDto(account, account.getStartBalance());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountDto update(Long id, AccountRequest request) {
        var account = accountRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Account", id));
        account.setName(request.name());
        account.setCurrency(request.currency());
        account.setType(request.type());
        account.setBank(resolveBank(request.bankId()));
        account.setAccountNumber(request.accountNumber());
        account.setStartBalance(request.startBalance());
        account.setStartDate(request.startDate());
        if (request.archived() != null) {
            account.setArchived(request.archived());
        }
        var balance = accountRepository.findAllBalances().stream()
                .filter(entry -> entry.getId().equals(id))
                .map(AccountRepository.AccountBalance::getBalance)
                .findFirst()
                .orElse(account.getStartBalance());
        return AccountMapper.INSTANCE.toDto(account, balance);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var account = accountRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Account", id));
        if (transactionRepository.existsByAccountIdOrCounterAccountId(id, id)) {
            throw new ConflictException("Account has transactions — archive it instead");
        }
        accountRepository.delete(account);
    }

    private Bank resolveBank(Long bankId) {
        if (bankId == null) {
            return null;
        }
        return bankRepository.findById(bankId)
                .orElseThrow(() -> NotFoundException.of("Bank", bankId));
    }
}
