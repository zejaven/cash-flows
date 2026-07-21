package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @author Stanislav Vafin
 */
public record BankRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 64) String parserId) {
}
