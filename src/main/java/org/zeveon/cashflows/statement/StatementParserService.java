package org.zeveon.cashflows.statement;

import java.nio.file.Path;

/**
 * @author Stanislav Vafin
 */
public interface StatementParserService {

    /**
     * Runs the registered parser over the PDF and returns the parsed statement.
     *
     * @throws StatementVerificationException checksum mismatch (parser exit 1) — 422
     * @throws RuntimeException               parser crash, bad invocation or timeout — 500
     */
    StatementDto parse(String parserId, Path pdf);
}
