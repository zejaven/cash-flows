package org.zeveon.cashflows.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.zeveon.cashflows.entity.enums.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @author Stanislav Vafin
 */
public record AccountRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 8) @Pattern(regexp = "[A-Z0-9]+",
                message = "must be an upper-case currency code") String currency,
        @NotNull AccountType type,
        Long bankId,
        @Size(max = 64) String accountNumber,
        @NotNull BigDecimal startBalance,
        @NotNull LocalDate startDate,
        Boolean archived) {
}
