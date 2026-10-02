package com.xupan.server.auth.security;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.PermissionService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Loads the current account and permissions from the database for every authentication. */
@Service
public class AuthenticatedUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PermissionService permissionService;

    public AuthenticatedUserDetailsService(UserRepository userRepository, PermissionService permissionService) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
    }

    @Override
    public AuthenticatedUser loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .map(this::toAuthenticatedUser)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }

    public AuthenticatedUser loadUserById(long userId) throws UsernameNotFoundException {
        return userRepository.findById(userId)
                .map(this::toAuthenticatedUser)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }

    private AuthenticatedUser toAuthenticatedUser(UserAccount user) {
        if (!userRepository.isLoginWindowOpen(user.id(), Instant.now())) {
            throw new UsernameNotFoundException("子账号已过期");
        }
        return new AuthenticatedUser(user, permissionService.toAuthorities(
                permissionService.findPermissionCodes(user.id())));
    }
}
