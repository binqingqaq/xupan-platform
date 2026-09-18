package com.xupan.server.system.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetTestPlayerBalanceRequest(
        @NotBlank @Size(max = 255) String reason,
        @NotBlank @Size(max = 128) String idempotencyKey
) {
}
