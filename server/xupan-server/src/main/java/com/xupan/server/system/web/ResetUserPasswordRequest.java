package com.xupan.server.system.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetUserPasswordRequest(@NotBlank @Size(max = 72) String rawPassword) {
}
