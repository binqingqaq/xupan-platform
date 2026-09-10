package com.xupan.server.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 72) String password,
        @Size(max = 128) String deviceLabel) {
}
