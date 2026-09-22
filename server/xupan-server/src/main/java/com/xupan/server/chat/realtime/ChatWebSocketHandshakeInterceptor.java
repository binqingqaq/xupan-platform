package com.xupan.server.chat.realtime;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.domain.WsTicket;
import com.xupan.server.auth.security.AuthenticatedUserDetailsService;
import com.xupan.server.auth.service.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

/** Authenticates the upgrade request without putting an access token in the WebSocket URL. */
@Component
public class ChatWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USER_ATTRIBUTE = ChatWebSocketHandshakeInterceptor.class.getName() + ".user";
    public static final String SESSION_ID_ATTRIBUTE = ChatWebSocketHandshakeInterceptor.class.getName() + ".sessionId";
    public static final String ROOM_CODE_ATTRIBUTE = ChatWebSocketHandshakeInterceptor.class.getName() + ".roomCode";
    public static final String CONNECTION_ATTRIBUTE = ChatWebSocketHandshakeInterceptor.class.getName() + ".connection";
    private static final Pattern ROOM_CODE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final TokenService tokenService;
    private final AuthenticatedUserDetailsService userDetailsService;

    public ChatWebSocketHandshakeInterceptor(TokenService tokenService,
                                             AuthenticatedUserDetailsService userDetailsService) {
        this.tokenService = tokenService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                  WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String roomCode = roomCode(request.getURI());
        String rawTicket = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("ticket");
        if (roomCode == null || rawTicket == null || rawTicket.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            WsTicket ticket = tokenService.consumeWsTicket(rawTicket, roomCode);
            AuthenticatedUser user = userDetailsService.loadUserById(ticket.userId());
            if ("CHAT_ONLY".equals(ticket.scope())) {
                user = user.asChatOnly();
            }
            if (!user.isEnabled() || !user.getAuthorities().stream()
                    .anyMatch(authority -> "PERM_CHAT_ROOM_READ".equals(authority.getAuthority()))) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            attributes.put(USER_ATTRIBUTE, user);
            attributes.put(SESSION_ID_ATTRIBUTE, ticket.sessionId());
            attributes.put(ROOM_CODE_ATTRIBUTE, roomCode);
            return true;
        } catch (TokenService.InvalidTokenException exception) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        } catch (RuntimeException exception) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Authentication failures are represented by the HTTP status above; do not log the ticket.
    }

    private static String roomCode(URI uri) {
        if (uri == null || uri.getPath() == null) {
            return null;
        }
        String path = uri.getPath();
        String prefix = "/ws/chat/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String value = path.substring(prefix.length());
        return ROOM_CODE.matcher(value).matches() ? value : null;
    }
}
