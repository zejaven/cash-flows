package org.zeveon.cashflows.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.model.dto.ImportDtos.*;
import org.zeveon.cashflows.repository.BankRepository;
import org.zeveon.cashflows.repository.ImportRunRepository;
import org.zeveon.cashflows.service.ImportService;
import org.zeveon.cashflows.statement.PendingImportHolder;
import org.zeveon.cashflows.statement.StatementParserService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Two-phase import facade: preview parses the statement file via the bank-bound
 * parser and parks the result in memory; confirm executes the user's mapping
 * decisions. The file format (PDF, CSV, zip …) is the parser's business.
 *
 * @author Stanislav Vafin
 */
@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    private final BankRepository bankRepository;
    private final ImportRunRepository importRunRepository;
    private final StatementParserService parserService;
    private final PendingImportHolder holder;
    private final ImportExecutor executor;

    @Override
    public ImportPreview preview(Long bankId, MultipartFile file) {
        var bank = bankRepository.findById(bankId)
                .orElseThrow(() -> NotFoundException.of("Bank", bankId));
        if (bank.getParserId() == null || bank.getParserId().isBlank()) {
            throw new ConflictException("Bank '%s' has no statement parser bound".formatted(bank.getName()));
        }

        Path temp = null;
        try {
            // keep the original extension — parsers may sniff the format by suffix
            var name = file.getOriginalFilename();
            var dot = name == null ? -1 : name.lastIndexOf('.');
            temp = Files.createTempFile("statement-", dot >= 0 ? name.substring(dot) : ".dat");
            file.transferTo(temp);
            var statement = parserService.parse(bank.getParserId(), temp);

            var token = holder.put(bankId, file.getOriginalFilename(), statement);
            var totalRows = statement.accounts().stream()
                    .mapToInt(account -> account.transactions().size())
                    .sum();
            return new ImportPreview(token, file.getOriginalFilename(),
                    statement.statement().periodFrom(), statement.statement().periodTo(),
                    totalRows,
                    executor.suggestAccounts(bank, statement),
                    executor.suggestCashTargets(statement));
        } catch (IOException e) {
            throw new RuntimeException("Could not store the uploaded file", e);
        } finally {
            deleteQuietly(temp);
        }
    }

    @Override
    public ImportReport confirm(ConfirmRequest request) {
        var pending = holder.get(request.token())
                .orElseThrow(() -> new NotFoundException(
                        "Import session expired — upload the statement again"));
        var bank = bankRepository.findById(pending.bankId())
                .orElseThrow(() -> NotFoundException.of("Bank", pending.bankId()));
        var report = executor.execute(bank, pending.fileName(), pending.statement(), request);
        holder.remove(request.token());
        return report;
    }

    @Override
    public List<ImportRunDto> history() {
        return importRunRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(run -> new ImportRunDto(run.getId(), run.getBank().getName(), run.getFileName(),
                        run.getPeriodFrom(), run.getPeriodTo(), run.getCreatedCount(),
                        run.getDuplicateCount(), run.getNeedsReviewCount(), run.getTransferCount(),
                        run.getCreatedAt()))
                .toList();
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // temp dir cleanup will get it eventually
            }
        }
    }
}
