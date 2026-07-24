package org.zeveon.cashflows.statement;

import java.nio.file.Path;
import java.util.List;

/**
 * @author Stanislav Vafin
 */
public interface LoanScheduleParserService {

    LoanScheduleDto parse(String parserId, Path file);

    List<ParserInfo> availableParsers();
}
