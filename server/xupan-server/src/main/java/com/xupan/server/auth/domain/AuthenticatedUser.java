package com.xupan.server.auth.domain;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Spring Security principal backed by a server-side user and permission set. */
public final class AuthenticatedUser implements UserDetails {

    private final UserAccount account;
    private final List<GrantedAuthority> authorities;
    private final String authMode;
    private final String scope;

    public AuthenticatedUser(UserAccount account, Collection<? extends GrantedAuthority> authorities) {
        this(account, authorities, account.authMode(), null);
    }

    public AuthenticatedUser(UserAccount account, Collection<? extends GrantedAuthority> authorities,
                             String authMode, String scope) {
        this.account = Objects.requireNonNull(account, "account");
        this.authorities = List.copyOf(authorities == null ? List.of() : authorities);
        this.authMode = authMode == null || authMode.isBlank() ? "PASSWORD" : authMode;
        this.scope = scope == null || scope.isBlank() ? null : scope;
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

    public String authMode() {
        return authMode;
    }

    public String scope() {
        return scope;
    }

    public boolean isChatOnly() {
        return "CHAT_ONLY".equals(scope);
    }

    public AuthenticatedUser asChatOnly() {
        Set<GrantedAuthority> chatAuthorities = new LinkedHashSet<>();
        chatAuthorities.add(new SimpleGrantedAuthority("SCOPE_CHAT_ONLY"));
        chatAuthorities.add(new SimpleGrantedAuthority("PERM_CHAT_ROOM_READ"));
        chatAuthorities.add(new SimpleGrantedAuthority("PERM_CHAT_MESSAGE_SEND"));
        return new AuthenticatedUser(account, chatAuthorities, "PLAYER_LINK", "CHAT_ONLY");
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
        if ("PLAYER_LINK".equals(authMode) && "TEST".equals(account.userType())) {
            Instant now = Instant.now();
            return account.lockedUntil() == null || !now.isBefore(account.lockedUntil());
        }
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
