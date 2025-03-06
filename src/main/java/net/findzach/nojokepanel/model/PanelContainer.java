package net.findzach.nojokepanel.model;

import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Zach Smith
 * @since 2/27/2025
 */
@Data
public class PanelContainer {
    private String id;
    private String name;
    private String image;
    private String domain;
    private int internalPort;
    private String status;
    private List<String> logs;
    private WebSocketSession webSocketSession;

    public PanelContainer(String id, String name, String image, String domain, int internalPort) {
        this.id = id;
        this.name = name;
        this.image = image;
        this.domain = domain;
        this.internalPort = internalPort;
        this.status = "BUILDING";
        this.logs = new ArrayList<>();
    }

    public PanelContainer() {
        this.logs = new ArrayList<>();
        this.status = "UNKNOWN";
    }

    public void addLog(String logEntry) {
        if (logEntry != null) {
            logs.add(logEntry);
        }
    }

    public void setWebSocketSession(WebSocketSession session) {
        this.webSocketSession = session;
    }
}