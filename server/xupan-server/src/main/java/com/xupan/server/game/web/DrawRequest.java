package com.xupan.server.game.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DrawRequest(
        @NotNull @Size(min = 8, max = 8) List<Integer> numbers
) {
}
