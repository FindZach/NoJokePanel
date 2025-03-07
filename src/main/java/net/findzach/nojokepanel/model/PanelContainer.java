package net.findzach.nojokepanel.model;

import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;

@Data
public class PanelContainer {
    private String id;
    private String name;
    private String imageName;
    private String domain;
    private int internalPort;
    private String status;
    private WebSocketSession webSocketSession;
    private List<String> logs = new ArrayList<>();

    public PanelContainer(String id, String name, String imageName, String domain, int internalPort) {
        this.id = id;
        this.name = name;
        this.imageName = imageName;
        this.domain = domain;
        this.internalPort = internalPort;
        this.status = "CREATING";
    }

    public void addLog(String log) {
        logs.add(log);
    }
}