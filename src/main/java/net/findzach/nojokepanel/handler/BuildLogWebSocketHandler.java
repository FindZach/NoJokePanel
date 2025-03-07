package net.findzach.nojokepanel.handler;

import lombok.extern.slf4j.Slf4j;
import net.findzach.nojokepanel.model.PanelContainer;
import net.findzach.nojokepanel.service.ContainerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class BuildLogWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    private ApplicationContext applicationContext; // Use application context to resolve dependencies

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String containerId = session.getUri().getQuery().split("=")[1]; // e.g., ?containerId=uuid
        sessions.put(containerId, session);
        log.info("WebSocket connection established for container ID: {}", containerId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("Received WebSocket message: {}", message.getPayload());
        String payload = message.getPayload();
        if (payload.startsWith("startBuild:")) {
            String[] parts = payload.split(":", 2);
            if (parts.length == 2) {
                String containerId = parts[1];
                log.info("Starting build for containerId: {}", containerId);
                ContainerService containerService = applicationContext.getBean(ContainerService.class);
                PanelContainer panelContainer = containerService.getContainer(containerId);
                if (panelContainer != null) {
                    log.info("Found container, streaming logs for containerId: {}", containerId);
                    containerService.streamBuildLogs(panelContainer);
                } else {
                    log.warn("Container not found for containerId: {}", containerId);
                    session.sendMessage(new TextMessage("Error: Container not found"));
                }
            } else {
                log.warn("Invalid message format: {}", payload);
            }
        } else {
            log.warn("Unknown message type: {}", payload);
        }
    }

    public void broadcastMessage(String containerId, String message) {
        WebSocketSession session = sessions.get(containerId);
        if (session != null && session.isOpen()) {
            try {
                log.info("Broadcasting message to containerId {}: {}", containerId, message);
                session.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                log.error("Failed to send WebSocket message for containerId {}: {}", containerId, e);
            }
        } else {
            log.warn("No open WebSocket session for containerId: {}", containerId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String containerId = session.getUri().getQuery().split("=")[1];
        sessions.remove(containerId);
        log.info("WebSocket connection closed for container ID: {}", containerId);
    }

}