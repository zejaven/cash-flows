package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @author Stanislav Vafin
 */
public record CounterpartyRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String note,
        Boolean archived) {
}
