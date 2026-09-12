package com.xupan.server.chat.realtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(ChatWebSocketProperties.class)
public class ChatWebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final ChatWebSocketHandshakeInterceptor interceptor;
    private final ChatWebSocketProperties properties;

    public ChatWebSocketConfig(ChatWebSocketHandler handler,
                               ChatWebSocketHandshakeInterceptor interceptor,
                               ChatWebSocketProperties properties) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat/{roomCode}")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns(properties.getAllowedOrigins().toArray(String[]::new));
    }
}
