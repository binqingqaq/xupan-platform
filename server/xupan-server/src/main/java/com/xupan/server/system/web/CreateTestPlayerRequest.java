package com.xupan.server.system.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTestPlayerRequest(
        @NotBlank @Size(max = 64) String userCode,
        @NotBlank @Size(max = 128) String displayName,
        @Size(max = 255) String avatarKey
) {
}
