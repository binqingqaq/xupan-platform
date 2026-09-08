package com.xupan.server.game.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record OddsRequest(
        @NotNull @DecimalMin("1.00") @Digits(integer = 6, fraction = 3) BigDecimal odds
) {
}
