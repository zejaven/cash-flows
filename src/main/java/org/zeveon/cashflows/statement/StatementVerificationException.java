package org.zeveon.cashflows.statement;

/**
 * The parser's built-in checksum verification failed (exit code 1) — the
 * statement totals don't match the parsed rows. Import is rejected as a whole
 * (FR-4.3), surfaced as HTTP 422.
 *
 * @author Stanislav Vafin
 */
public class StatementVerificationException extends RuntimeException {

    public StatementVerificationException(String message) {
        super(message);
    }
}
