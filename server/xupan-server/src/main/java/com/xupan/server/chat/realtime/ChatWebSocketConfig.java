package com.xupan.server.chat.realtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties({ChatWebSocketProperties.class,
        com.xupan.server.chat.service.ChatMessageProperties.class})
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

    @Bean
    @ConditionalOnProperty(prefix = "xupan.chat.websocket", name = "container-configured",
            havingValue = "true", matchIfMissing = true)
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        int maxWireMessageBytes = Math.max(properties.getMaxMessageBytes(),
                properties.getMaxOutboundMessageBytes());
        container.setMaxTextMessageBufferSize(maxWireMessageBytes);
        container.setMaxBinaryMessageBufferSize(maxWireMessageBytes);
        return container;
    }
}
