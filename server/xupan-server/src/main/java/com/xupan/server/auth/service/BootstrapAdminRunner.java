package com.xupan.server.auth.service;

import com.xupan.server.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordPolicyService passwordPolicy;
    private final boolean enabled;
    private final String username;
    private final String password;

    public BootstrapAdminRunner(UserRepository userRepository, PasswordPolicyService passwordPolicy,
                                @Value("${xupan.auth.bootstrap-admin.enabled:false}") boolean enabled,
                                @Value("${xupan.auth.bootstrap-admin.username:}") String username,
                                @Value("${xupan.auth.bootstrap-admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordPolicy = passwordPolicy;
        this.enabled = enabled;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || userRepository.existsAnyUser()) {
            return;
        }
        if (username == null || username.isBlank() || username.length() > 64
                || password == null || password.isBlank()) {
            throw new IllegalStateException("管理员初始化已启用，但外部用户名或密码配置缺失");
        }
        String passwordHash = passwordPolicy.encode(password);
        long userId = userRepository.insert(username, username, passwordHash, "ACTIVE");
        if (userRepository.assignRole(userId, "ADMIN") != 1) {
            throw new IllegalStateException("管理员初始化失败：ADMIN 角色不存在");
        }
    }
}
