package com.xupan.server.system.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateUserRolesRequest(@NotNull @Size(max = 8) List<@Valid String> roleCodes) {
}
