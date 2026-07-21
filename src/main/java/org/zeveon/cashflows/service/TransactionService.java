package org.zeveon.cashflows.service;

import org.zeveon.cashflows.model.TransactionFilter;
import org.zeveon.cashflows.model.dto.BulkCategoryRequest;
import org.zeveon.cashflows.model.dto.PageDto;
import org.zeveon.cashflows.model.dto.TransactionDto;
import org.zeveon.cashflows.model.dto.TransactionRequest;

/**
 * @author Stanislav Vafin
 */
public interface TransactionService {

    PageDto<TransactionDto> find(TransactionFilter filter, int page, int size);

    TransactionDto create(TransactionRequest request);

    TransactionDto update(Long id, TransactionRequest request);

    void delete(Long id);

    void bulkCategory(BulkCategoryRequest request);
}
