package org.zeveon.cashflows.model.dto;

/**
 * @author Stanislav Vafin
 */
public record CounterpartyDto(Long id, String name, String note, boolean archived, long debtCount) {
}
