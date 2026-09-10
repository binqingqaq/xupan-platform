package com.xupan.server.game.web;

import com.xupan.server.game.domain.PlayType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record PlaceBetRequest(
        @NotNull @Min(1) @Max(8) Integer ballNumber,
        @NotNull PlayType playType,
        List<Integer> parameters,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal stake,
        @NotBlank @Size(max = 128) String idempotencyKey
) {
}
