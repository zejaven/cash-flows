package org.zeveon.cashflows.service;

import org.springframework.web.multipart.MultipartFile;
import org.zeveon.cashflows.model.dto.ImportDtos.ConfirmRequest;
import org.zeveon.cashflows.model.dto.ImportDtos.ImportPreview;
import org.zeveon.cashflows.model.dto.ImportDtos.ImportReport;
import org.zeveon.cashflows.model.dto.ImportDtos.ImportRunDto;

import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface ImportService {

    ImportPreview preview(Long bankId, MultipartFile file);

    ImportReport confirm(ConfirmRequest request);

    List<ImportRunDto> history();
}
