package com.xupan.server.system.web;

import com.xupan.server.system.service.UserAdminService;

import java.time.Instant;
import java.util.List;

public record UserPageResponse(List<UserSummary> items, int page, int pageSize, long total) {
    public static UserPageResponse from(UserAdminService.UserPage page) {
        return new UserPageResponse(page.items().stream().map(UserSummary::from).toList(),
                page.page(), page.pageSize(), page.total());
    }

    public record UserSummary(long id, String username, String displayName, String avatarKey, String status,
                              List<String> roles, Instant createdAt, Instant lastLoginAt) {
        static UserSummary from(UserAdminService.UserSummary user) {
            return new UserSummary(user.id(), user.username(), user.displayName(), user.avatarKey(), user.status(),
                    user.roles(), user.createdAt(), user.lastLoginAt());
        }
    }
}
