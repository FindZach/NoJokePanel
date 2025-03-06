package net.findzach.nojokepanel.controller;

import net.findzach.nojokepanel.model.PanelContainer;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Controller
@RequestMapping("/")
@Slf4j
public class DockerController extends TextWebSocketHandler {

    private final DockerClient dockerClient;
    private final Map<String, PanelContainer> containers = new HashMap<>(); // In-memory storage for containers
    private String traefikNetwork;

    public DockerController() {
        String dockerHost = System.getenv("DOCKER_HOST") != null ? System.getenv("DOCKER_HOST") : "unix:///var/run/docker.sock";
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    // Home page - list containers
    @GetMapping
    public String home(Model model) {
        model.addAttribute("containers", containers.values());
        return "index";
    }

    // Deploy GitHub repo form
    @GetMapping("/deploy")
    public String deployForm(Model model) {
        model.addAttribute("githubDeploy", new GitHubDeploy());
        return "deploy";
    }

    @Autowired
    private Environment environment;

    // Handle GitHub repo deployment
    @PostMapping("/deploy")
    public String deployGitHubRepo(@ModelAttribute GitHubDeploy githubDeploy, Model model) {
        try {
            String tempDir = "repo-" + System.currentTimeMillis();
            new File(tempDir).mkdir();

            cloneRepoWithToken(githubDeploy.getRepoUrl(), githubDeploy.getGithubToken(), tempDir);
            Path packPath = downloadAndInstallPack(tempDir);
            String imageName = "app-" + System.currentTimeMillis() + ":latest";
            String containerId = imageName.replace(":", "-");

            // Create and store Container object
            PanelContainer panelContainer = new PanelContainer(containerId, "github-" + System.currentTimeMillis(), imageName, githubDeploy.getDomain(), githubDeploy.getInternalPort());
            containers.put(containerId, panelContainer);

            // Start building and stream logs
            buildWithPaketo(packPath.toString(), tempDir, imageName, panelContainer);

            // Deploy container with Traefik for SSL and domain
            CreateContainerResponse dockerContainer = dockerClient.createContainerCmd(imageName)
                    .withName(panelContainer.getName())
                    .withHostConfig(HostConfig.newHostConfig()
                            .withNetworkMode(traefikNetwork))
                    .withExposedPorts(ExposedPort.tcp(githubDeploy.getInternalPort()))
                    .withLabels(Map.of(
                            "traefik.enable", "true",
                            "traefik.http.routers." + panelContainer.getName() + ".rule", "Host(`" + githubDeploy.getDomain() + "`)",
                            "traefik.http.routers." + panelContainer.getName() + ".entrypoints", "websecure",
                            "traefik.http.routers." + panelContainer.getName() + ".tls", "true",
                            "traefik.http.routers." + panelContainer.getName() + ".tls.certresolver", "myresolver",
                            "traefik.http.services." + panelContainer.getName() + ".loadbalancer.server.port", String.valueOf(githubDeploy.getInternalPort())
                    ))
                    .exec();
            dockerClient.startContainerCmd(dockerContainer.getId()).exec();
            panelContainer.setId(dockerContainer.getId());
            panelContainer.setStatus("RUNNING");

            deleteDirectory(new File(tempDir));

            model.addAttribute("message", "GitHub repo deployed successfully. Access at https://" + githubDeploy.getDomain());
            model.addAttribute("container", panelContainer);
            return "result";
        } catch (Exception e) {
            log.error("GitHub deployment failed", e);
            model.addAttribute("error", "Deployment failed: " + e.getMessage());
            return "error";
        }
    }

    // View container details and logs
    @GetMapping("/container/{id}")
    public String containerDetails(@PathVariable String id, Model model) {
        PanelContainer panelContainer = containers.get(id);
        if (panelContainer == null) {
            model.addAttribute("error", "Container not found");
            return "error";
        }
        model.addAttribute("container", panelContainer);
        return "container";
    }

    // WebSocket connection handler
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String containerId = session.getUri().getQuery().split("=")[1]; // Extract containerId from URL, e.g., ?containerId=uuid
        PanelContainer panelContainer = containers.get(containerId);
        if (panelContainer != null) {
            panelContainer.setWebSocketSession(session);
            log.info("WebSocket connection established for container ID: {}", containerId);
        } else {
            try {
                session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Container not found"));
            } catch (IOException e) {
                log.error("Failed to close WebSocket session", e);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String containerId = session.getUri().getQuery().split("=")[1];
        PanelContainer panelContainer = containers.get(containerId);
        if (panelContainer != null) {
            panelContainer.setWebSocketSession(null);
            log.info("WebSocket connection closed for container ID: {}", containerId);
        }
    }

    // Control actions (stop, start, restart, remove)
    @PostMapping("/container/{id}/stop")
    public String stopContainer(@PathVariable String id, Model model) {
        try {
            PanelContainer panelContainer = containers.get(id);
            if (panelContainer == null) {
                model.addAttribute("error", "Container not found");
                return "error";
            }
            dockerClient.stopContainerCmd(id).withTimeout(10).exec();
            panelContainer.setStatus("STOPPED");
            model.addAttribute("message", "Container " + id + " stopped");
            model.addAttribute("container", panelContainer);
            return "result";
        } catch (Exception e) {
            log.error("Stop container failed", e);
            model.addAttribute("error", "Failed: " + e.getMessage());
            return "error";
        }
    }

    @PostMapping("/container/{id}/start")
    public String startContainer(@PathVariable String id, Model model) {
        try {
            PanelContainer panelContainer = containers.get(id);
            if (panelContainer == null) {
                model.addAttribute("error", "Container not found");
                return "error";
            }
            dockerClient.startContainerCmd(id).exec();
            panelContainer.setStatus("RUNNING");
            model.addAttribute("message", "Container " + id + " started");
            model.addAttribute("container", panelContainer);
            return "result";
        } catch (Exception e) {
            log.error("Start container failed", e);
            model.addAttribute("error", "Failed: " + e.getMessage());
            return "error";
        }
    }

    @PostMapping("/container/{id}/restart")
    public String restartContainer(@PathVariable String id, Model model) {
        try {
            PanelContainer panelContainer = containers.get(id);
            if (panelContainer == null) {
                model.addAttribute("error", "Container not found");
                return "error";
            }
            dockerClient.restartContainerCmd(id).withTimeout(10).exec();
            panelContainer.setStatus("RUNNING");
            model.addAttribute("message", "Container " + id + " restarted");
            model.addAttribute("container", panelContainer);
            return "result";
        } catch (Exception e) {
            log.error("Restart container failed", e);
            model.addAttribute("error", "Failed: " + e.getMessage());
            return "error";
        }
    }

    @PostMapping("/container/{id}/remove")
    public String removeContainer(@PathVariable String id, Model model) {
        try {
            PanelContainer panelContainer = containers.get(id);
            if (panelContainer == null) {
                model.addAttribute("error", "Container not found");
                return "error";
            }
            dockerClient.removeContainerCmd(id).withForce(true).exec();
            containers.remove(id);
            model.addAttribute("message", "Container " + id + " removed");
            return "result";
        } catch (Exception e) {
            log.error("Remove container failed", e);
            model.addAttribute("error", "Failed: " + e.getMessage());
            return "error";
        }
    }

    // Helper: Clone GitHub repo with token
    private void cloneRepoWithToken(String repoUrl, String githubToken, String cloneDir) throws IOException {
        File dir = new File(cloneDir);
        if (dir.exists()) {
            deleteDirectory(dir);
        }
        String authRepoUrl = repoUrl.replace("https://", "https://" + githubToken + "@");
        CommandLine cmd = CommandLine.parse("git clone " + authRepoUrl + " " + cloneDir);
        DefaultExecutor executor = new DefaultExecutor();
        executor.execute(cmd);
        log.info("Cloned GitHub repo to {}", cloneDir);
    }


    private Path downloadAndInstallPack(String tempDir) throws IOException, InterruptedException {
        String packVersion = "0.32.0";
        String packUrl = "https://github.com/buildpacks/pack/releases/download/v" + packVersion + "/pack-v" + packVersion + "-linux.tgz";

        Path packTgzPath = Path.of(tempDir, "pack.tgz");
        log.info("Downloading pack from {}", packUrl);
        try (InputStream in = new URL(packUrl).openStream()) {
            Files.copy(in, packTgzPath);
        }

        Path packDir = Path.of(tempDir, "pack");
        Files.createDirectories(packDir);
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", packTgzPath.toString(), "-C", packDir.toString());
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException("Failed to extract pack.tgz, exit code: " + exitCode);
        }

        Path packBinary = packDir.resolve("pack");
        Files.list(packDir).forEach(file -> log.info("Extracted file: {}", file));
        if (!Files.exists(packBinary)) {
            throw new IOException("Pack binary not found at " + packBinary);
        }

        Files.setPosixFilePermissions(packBinary, PosixFilePermissions.fromString("rwxr-xr-x"));
        log.info("Pack binary installed at {}", packBinary);

        return Path.of(packBinary.toString()); // Return as string for buildWithPaketo
    }

    private void buildWithPaketo(String packPath, String cloneDir, String imageName, PanelContainer panelContainer) throws IOException, InterruptedException {
        log.info("Building with pack at: {}", packPath);
        File packFile = new File(packPath);
        if (!packFile.exists() || !packFile.canExecute()) {
            throw new IOException("Pack binary not found or not executable at " + packPath);
        }
        // Use relative path since working directory is cloneDir
        String relativePackPath = new File(cloneDir).toPath().relativize(new File(packPath).toPath()).toString();
        CommandLine cmd = new CommandLine(relativePackPath)
                .addArgument("build")
                .addArgument(imageName)
                .addArgument("--path")
                .addArgument(".")
                .addArgument("--builder")
                .addArgument("paketobuildpacks/builder-jammy-base");
        DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory(new File(cloneDir));
        WebSocketStreamHandler streamHandler = new WebSocketStreamHandler(panelContainer);
        executor.setStreamHandler(streamHandler);
        log.info("Starting pack build for image: {}", imageName);
        try {
            int exitCode = executor.execute(cmd);
            if (exitCode != 0) {
                throw new IOException("Pack build failed with exit code " + exitCode + ". Error: " + streamHandler.getErrorOutput());
            }
            panelContainer.setStatus("BUILD_COMPLETE");
            log.info("Built Docker image with Paketo Buildpacks: {}", imageName);
        } catch (Exception e) {
            log.error("Pack build failed: {}", e.getMessage());
            throw new RuntimeException(e);
        } finally {
            if (panelContainer.getWebSocketSession() != null && panelContainer.getWebSocketSession().isOpen()) {
                try {
                    panelContainer.getWebSocketSession().sendMessage(new TextMessage("Build completed successfully"));
                } catch (IOException e) {
                    log.error("Failed to send completion message", e);
                }
            }
        }
    }
    // Helper: Delete directory
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    // Custom StreamHandler for WebSocket log streaming
    private class WebSocketStreamHandler implements ExecuteStreamHandler {

        private final PanelContainer panelContainer;
        private final StringBuilder errorOutput = new StringBuilder();
        private InputStream inputStream;
        private OutputStream outputStream;
        private InputStream errorStream;

        public WebSocketStreamHandler(PanelContainer panelContainer) {
            this.panelContainer = panelContainer;
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
                        WebSocketSession session = panelContainer.getWebSocketSession();
                        if (session != null && session.isOpen()) {
                            session.sendMessage(new TextMessage(line));
                        }
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
                        WebSocketSession session = panelContainer.getWebSocketSession();
                        if (session != null && session.isOpen()) {
                            session.sendMessage(new TextMessage("ERROR: " + line));
                        }
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

    // Model for GitHub deployment form
    public static class GitHubDeploy {
        private String repoUrl;
        private String githubToken;
        private String domain;
        private int internalPort;

        // Getters and setters
        public String getRepoUrl() { return repoUrl; }
        public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
        public String getGithubToken() { return githubToken; }
        public void setGithubToken(String githubToken) { this.githubToken = githubToken; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public int getInternalPort() { return internalPort; }
        public void setInternalPort(int internalPort) { this.internalPort = internalPort; }
    }
}