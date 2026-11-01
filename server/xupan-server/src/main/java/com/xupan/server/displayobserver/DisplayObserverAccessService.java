package com.xupan.server.displayobserver;

import com.xupan.server.auth.domain.AuthenticatedUser;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class DisplayObserverAccessService {

    public static final String SCOPE_PREFIX = "DRO:";
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9_-]{16}");

    private final DisplayObserverBindingService bindingService;

    public DisplayObserverAccessService(DisplayObserverBindingService bindingService) {
        this.bindingService = bindingService;
    }

    public Optional<ResolvedObserver> resolve(String publicId) {
        if (publicId == null || !PUBLIC_ID.matcher(publicId).matches()) {
            return Optional.empty();
        }
        return bindingService.resolvePublicId(publicId)
                .map(binding -> new ResolvedObserver(binding.publicId(), binding.agentId(),
                        binding.viewerUserId(), binding.version()));
    }

    public Optional<AuthenticatedUser> principal(AuthenticatedUser base, String scope) {
        if (base == null || scope == null || !scope.startsWith(SCOPE_PREFIX)) {
            return Optional.empty();
        }
        String publicId = scope.substring(SCOPE_PREFIX.length());
        return resolve(publicId)
                .filter(binding -> binding.viewerUserId() == base.getUserId())
                .map(ignored -> base.asDisplayObserver(scope))
                .filter(user -> user.isEnabled() && user.isAccountNonLocked());
    }

    public boolean isCurrent(long userId, String scope) {
        if (scope == null || !scope.startsWith(SCOPE_PREFIX)) return false;
        String publicId = scope.substring(SCOPE_PREFIX.length());
        return resolve(publicId).map(binding -> binding.viewerUserId() == userId).orElse(false);
    }

    public record ResolvedObserver(String publicId, long agentId, long viewerUserId, long bindingVersion) {
        public String scope() {
            return SCOPE_PREFIX + publicId;
        }
    }
}
