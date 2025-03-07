package net.findzach.nojokepanel.util;

import net.findzach.nojokepanel.handler.BuildLogWebSocketHandler;
import net.findzach.nojokepanel.model.PanelContainer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.ExecuteStreamHandler;

import java.io.*;


@Slf4j
public class WebSocketStreamHandler implements ExecuteStreamHandler {

    private final PanelContainer panelContainer;
    private final BuildLogWebSocketHandler webSocketHandler;
    private final StringBuilder errorOutput = new StringBuilder();
    private InputStream inputStream;
    private OutputStream outputStream;
    private InputStream errorStream;

    public WebSocketStreamHandler(PanelContainer panelContainer, BuildLogWebSocketHandler webSocketHandler) {
        this.panelContainer = panelContainer;
        this.webSocketHandler = webSocketHandler;
    }

    @Override
    public void setProcessInputStream(OutputStream os) throws IOException {
        this.outputStream = os;
    }

    @Override
    public void setProcessErrorStream(InputStream is) throws IOException {
        this.errorStream = is;
        startErrorStreamReader();
    }

    @Override
    public void setProcessOutputStream(InputStream is) throws IOException {
        this.inputStream = is;
        startOutputStreamReader();
    }

    @Override
    public void start() throws IOException {
        // Streams are handled in setProcessOutputStream and setProcessErrorStream
    }

    @Override
    public void stop() {
        try {
            if (inputStream != null) inputStream.close();
            if (errorStream != null) errorStream.close();
            if (outputStream != null) outputStream.close();
        } catch (IOException e) {
            log.error("Error closing streams", e);
        }
    }

    private void startOutputStreamReader() {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    panelContainer.addLog(line);
                    webSocketHandler.broadcastMessage(panelContainer.getId(), line);
                    log.info("Build output for container {}: {}", panelContainer.getId(), line);
                }
            } catch (IOException e) {
                log.error("Error reading output stream for container {}", panelContainer.getId(), e);
            }
        }).start();
    }

    private void startErrorStreamReader() {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                    panelContainer.addLog("ERROR: " + line);
                    webSocketHandler.broadcastMessage(panelContainer.getId(), "ERROR: " + line);
                    log.error("Build error for container {}: {}", panelContainer.getId(), line);
                }
            } catch (IOException e) {
                log.error("Error reading error stream for container {}", panelContainer.getId(), e);
            }
        }).start();
    }

    public String getErrorOutput() {
        return errorOutput.toString();
    }
}