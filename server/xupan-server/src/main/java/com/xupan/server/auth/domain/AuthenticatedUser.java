package com.xupan.server.auth.domain;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Spring Security principal backed by a server-side user and permission set. */
public final class AuthenticatedUser implements UserDetails {

    private final UserAccount account;
    private final List<GrantedAuthority> authorities;

    public AuthenticatedUser(UserAccount account, Collection<? extends GrantedAuthority> authorities) {
        this.account = Objects.requireNonNull(account, "account");
        this.authorities = List.copyOf(authorities == null ? List.of() : authorities);
    }

    public long getUserId() {
        return account.id();
    }

    public String getDisplayName() {
        return account.displayName();
    }

    public long getSecurityVersion() {
        return account.securityVersion();
    }

    public UserAccount account() {
        return account;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return account.passwordHash();
    }

    @Override
    public String getUsername() {
        return account.username();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return account.canLogin(Instant.now());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return "ACTIVE".equals(account.status()) || "LOCKED".equals(account.status());
    }
}
