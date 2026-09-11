package com.xupan.server.system.web;

import com.xupan.server.system.service.UserAdminService;

public record RoleOptionResponse(long id, String code, String name, String status) {
    public static RoleOptionResponse from(UserAdminService.RoleOption role) {
        return new RoleOptionResponse(role.id(), role.code(), role.displayName(), role.status());
    }
}
