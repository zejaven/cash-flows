package org.zeveon.cashflows.statement;

/**
 * A statement parser discovered in the scripts directory (FR-4.2): the id is
 * derived from the script file name by convention ({@code <stem>.py} ⇄
 * {@code <stem>-pdf}).
 *
 * @author Stanislav Vafin
 */
public record ParserInfo(String id, String script) {
}
