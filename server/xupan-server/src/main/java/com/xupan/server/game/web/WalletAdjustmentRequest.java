package com.xupan.server.game.web;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record WalletAdjustmentRequest(
        @NotNull @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 255) String reason,
        @NotBlank @Size(max = 128) String idempotencyKey
) {
}
