package com.xupan.server.auth.web;

import com.xupan.server.auth.service.AuthenticationService;

import java.math.BigDecimal;
import java.util.List;

/**
 * 当前登录用户响应。
 *
 * <p>{@code subAccountScore} 只在当前用户是子账号（{@code agent_group.sys_user_id} 命中）时有值，
 * 对应 BY220 {@code head.html:192} 顶部显示的「可用积分：N」（{@code x_admin.score}）；
 * 其它身份返回 {@code null}。</p>
 */
public record CurrentUserResponse(long id, String username, String displayName,
                                  String avatarKey, String authMode, String scope,
                                  List<String> roles, List<String> permissions,
                                  BigDecimal subAccountScore) {

    public static CurrentUserResponse from(AuthenticationService.CurrentUser user) {
        return new CurrentUserResponse(user.id(), user.username(), user.displayName(), user.avatarKey(),
                user.authMode(), user.scope(), user.roles(), user.permissions(), user.subAccountScore());
    }
}
