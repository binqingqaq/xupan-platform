package com.xupan.server.chat.realtime;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.SessionRecord;
import com.xupan.server.auth.repository.SessionRepository;
import com.xupan.server.auth.security.AuthenticatedUserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.time.Instant;
import java.util.Optional;

/** Revalidates the database-backed access context of an established chat connection. */
@Component
public class ChatConnectionAccessService {

    private static final String ROOM_READ_PERMISSION = "PERM_CHAT_ROOM_READ";

    private final SessionRepository sessionRepository;
    private final AuthenticatedUserDetailsService userDetailsService;

    public ChatConnectionAccessService(SessionRepository sessionRepository,
                                       AuthenticatedUserDetailsService userDetailsService) {
        this.sessionRepository = sessionRepository;
        this.userDetailsService = userDetailsService;
    }

    public AccessCheck check(ChatConnection connection, Instant now) {
        if (connection == null || now == null) {
            return AccessCheck.unauthenticated();
        }
        try {
            AuthenticatedUser user = userDetailsService.loadUserById(connection.userId());
            Optional<SessionRecord> session = sessionRepository.findBySessionId(connection.sessionId());
            if (session.isEmpty() || session.get().userId() != connection.userId()
                    || !session.get().isAccessTokenValid(now, user.getSecurityVersion())) {
                return AccessCheck.unauthenticated();
            }
            if (session.get().isChatOnly()) {
                user = user.asChatOnly();
            }
            if (!user.isEnabled() || !user.isAccountNonLocked()) {
                return AccessCheck.forbidden();
            }
            boolean canRead = user.getAuthorities().stream()
                    .anyMatch(authority -> ROOM_READ_PERMISSION.equals(authority.getAuthority()));
            return canRead ? AccessCheck.allowed(user) : AccessCheck.forbidden();
        } catch (RuntimeException exception) {
            // Fail closed when the current access context cannot be loaded.
            return AccessCheck.unauthenticated();
        }
    }

    public enum Status {
        ALLOWED,
        UNAUTHENTICATED,
        FORBIDDEN
    }

    public record AccessCheck(Status status, AuthenticatedUser user) {

        public static AccessCheck allowed(AuthenticatedUser user) {
            return new AccessCheck(Status.ALLOWED, user);
        }

        public static AccessCheck unauthenticated() {
            return new AccessCheck(Status.UNAUTHENTICATED, null);
        }

        public static AccessCheck forbidden() {
            return new AccessCheck(Status.FORBIDDEN, null);
        }

        public boolean allowed() {
            return status == Status.ALLOWED;
        }

        public CloseStatus closeStatus() {
            return status == Status.FORBIDDEN
                    ? new CloseStatus(4403, "chat permission denied")
                    : new CloseStatus(4401, "chat authentication expired");
        }
    }
}
