package net.findzach.nojokepanel.config;

import net.findzach.nojokepanel.controller.DockerController;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * @author Zach Smith
 * @since 2/27/2025
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DockerController dockerController;

    public WebSocketConfig(DockerController dockerController) {
        this.dockerController = dockerController;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(dockerController, "/websocket").setAllowedOrigins("*");
    }
}