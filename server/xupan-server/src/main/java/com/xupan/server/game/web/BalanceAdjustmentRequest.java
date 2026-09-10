package com.xupan.server.game.web;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record BalanceAdjustmentRequest(
        @NotNull @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank String reason
) {
}
