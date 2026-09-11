package com.xupan.server.system.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeUserStatusRequest(@NotBlank @Size(max = 16) String status) {
}
