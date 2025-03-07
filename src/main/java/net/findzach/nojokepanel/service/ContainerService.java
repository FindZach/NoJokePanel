package net.findzach.nojokepanel.service;

import net.findzach.nojokepanel.handler.BuildLogWebSocketHandler;
import net.findzach.nojokepanel.model.GitHubDeploy;
import net.findzach.nojokepanel.model.PanelContainer;
import net.findzach.nojokepanel.util.BuildExecutor;
import net.findzach.nojokepanel.util.WebSocketStreamHandler;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ContainerService implements ContainerServiceInterface {

    private final DockerClient dockerClient;
    private final Map<String, PanelContainer> containers = new ConcurrentHashMap<>();
    private final Map<String, GitHubDeploy> deployments = new ConcurrentHashMap<>(); // Store GitHubDeploy objects
    private final BuildLogWebSocketHandler webSocketHandler;
    private String traefikNetwork = "traefik-net";

    @Autowired
    public ContainerService(BuildLogWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
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

    @Override
    public Map<String, PanelContainer> getContainers() {
        return containers;
    }

    @Override
    public PanelContainer getContainer(String id) {
        return containers.get(id);
    }

    @Override
    public PanelContainer initiateDeployment(GitHubDeploy githubDeploy) {
        log.info("Initiating deployment for repoUrl: {}, domain: {}, port: {}",
                githubDeploy.getRepoUrl(), githubDeploy.getDomain(), githubDeploy.getInternalPort());
        String tempDir = "repo-" + System.currentTimeMillis();
        new File(tempDir).mkdir();

        String imageName = "app-" + System.currentTimeMillis() + ":latest";
        String containerId = imageName.replace(":", "-");

        PanelContainer panelContainer = new PanelContainer(containerId, "github-" + System.currentTimeMillis(),
                imageName, githubDeploy.getDomain(), githubDeploy.getInternalPort());
        containers.put(containerId, panelContainer);
        deployments.put(containerId, githubDeploy); // Store the original GitHubDeploy object
        return panelContainer;
    }

    public void completeDeployment(PanelContainer panelContainer) throws Exception {
        String containerId = panelContainer.getId();
        GitHubDeploy githubDeploy = deployments.get(containerId); // Retrieve the original deploy data
        if (githubDeploy == null) {
            throw new Exception("No deployment data found for containerId: " + containerId);
        }

        String tempDir = "repo-" + containerId.replace("-", "");
        new File(tempDir).mkdir();

        try {
            cloneRepoWithToken(githubDeploy.getRepoUrl(), githubDeploy.getGithubToken(), tempDir);
            Path packPath = downloadAndInstallPack(tempDir);
            buildWithPaketo(packPath.toString(), tempDir, panelContainer.getImageName(), panelContainer);

            CreateContainerResponse dockerContainer = dockerClient.createContainerCmd(panelContainer.getImageName())
                    .withName(panelContainer.getName())
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode(traefikNetwork))
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
        } catch (Exception e) {
            log.error("Deployment failed in completeDeployment for containerId {}: {}", panelContainer.getId(), e.getMessage(), e);
            throw e;
        } finally {
            deleteDirectory(new File(tempDir));
        }
    }

    @Override
    @Deprecated // Use initiateDeployment and completeDeployment instead
    public PanelContainer deployGitHubRepo(GitHubDeploy githubDeploy) throws Exception {
        log.warn("Deprecated method deployGitHubRepo called. Use initiateDeployment and completeDeployment instead.");
        return initiateDeployment(githubDeploy); // Placeholder to maintain compatibility
    }

    @Override
    public PanelContainer stopContainer(String id) throws Exception {
        PanelContainer panelContainer = containers.get(id);
        if (panelContainer == null) throw new Exception("Container not found");
        dockerClient.stopContainerCmd(id).withTimeout(10).exec();
        panelContainer.setStatus("STOPPED");
        return panelContainer;
    }

    @Override
    public PanelContainer startContainer(String id) throws Exception {
        PanelContainer panelContainer = containers.get(id);
        if (panelContainer == null) throw new Exception("Container not found");
        dockerClient.startContainerCmd(id).exec();
        panelContainer.setStatus("RUNNING");
        return panelContainer;
    }

    @Override
    public PanelContainer restartContainer(String id) throws Exception {
        PanelContainer panelContainer = containers.get(id);
        if (panelContainer == null) throw new Exception("Container not found");
        dockerClient.restartContainerCmd(id).withTimeout(10).exec();
        panelContainer.setStatus("RUNNING");
        return panelContainer;
    }

    @Override
    public void removeContainer(String id) throws Exception {
        PanelContainer panelContainer = containers.get(id);
        if (panelContainer == null) throw new Exception("Container not found");
        dockerClient.removeContainerCmd(id).withForce(true).exec();
        containers.remove(id);
    }

    @Override
    public void streamBuildLogs(PanelContainer panelContainer) {
        try {
            log.info("Delaying build start for container {} to ensure WebSocket connection", panelContainer.getId());
            Thread.sleep(5000); // Delay 5 seconds to allow WebSocket connection
        } catch (InterruptedException e) {
            log.error("Delay interrupted", e);
        }
        new BuildExecutor(panelContainer, webSocketHandler).executeBuild();
    }

    private void cloneRepoWithToken(String repoUrl, String githubToken, String cloneDir) throws IOException {
        File dir = new File(cloneDir);
        if (dir.exists()) deleteDirectory(dir);
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
        try (InputStream in = new URL(packUrl).openStream()) {
            Files.copy(in, packTgzPath);
        }
        Path packDir = Path.of(tempDir, "pack");
        Files.createDirectories(packDir);
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", packTgzPath.toString(), "-C", packDir.toString());
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) throw new IOException("Failed to extract pack.tgz, exit code: " + exitCode);
        Path packBinary = packDir.resolve("pack");
        if (!Files.exists(packBinary)) throw new IOException("Pack binary not found at " + packBinary);
        Files.setPosixFilePermissions(packBinary, PosixFilePermissions.fromString("rwxr-xr-x"));
        log.info("Pack binary installed at {}", packBinary);
        return packBinary;
    }

    private void buildWithPaketo(String packPath, String cloneDir, String imageName, PanelContainer panelContainer) throws IOException, InterruptedException {
        log.info("Building with pack at: {}", packPath);
        File packFile = new File(packPath);
        if (!packFile.exists() || !packFile.canExecute()) {
            throw new IOException("Pack binary not found or not executable at " + packPath);
        }
        String relativePackPath = new File(cloneDir).toPath().relativize(new File(packPath).toPath()).toString();
        CommandLine cmd = new CommandLine(relativePackPath)
                .addArgument("build")
                .addArgument(imageName)
                .addArgument("--path")
                .addArgument(".")
                .addArgument("--builder")
                .addArgument("paketobuildpacks/builder-jammy-base")
                .addArgument("BP_JVM_VERSION=17"); // Specify JDK 17
        DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory(new File(cloneDir));
        WebSocketStreamHandler streamHandler = new WebSocketStreamHandler(panelContainer, webSocketHandler); // Manual instantiation
        executor.setStreamHandler(streamHandler);
        log.info("Starting pack build for image: {}", imageName);
        int exitCode = executor.execute(cmd);
        if (exitCode != 0) {
            throw new IOException("Pack build failed with exit code " + exitCode + ". Error: " + streamHandler.getErrorOutput());
        }
        panelContainer.setStatus("BUILD_COMPLETE");
        log.info("Built Docker image with Paketo Buildpacks: {}", imageName);
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) deleteDirectory(file);
                else file.delete();
            }
        }
        dir.delete();
    }
}