package com.modernstock.money;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** Pay this much (USD) of one invoice or bill. */
public record AllocationRequest(@Schema(description = "Invoice ID or bill ID") @NotNull Long documentId,
                                @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
}
