package com.xupan.server.system.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 128) String displayName,
        @NotBlank @Size(max = 72) String rawPassword
) {
}
