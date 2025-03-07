package net.findzach.nojokepanel.util;

import net.findzach.nojokepanel.handler.BuildLogWebSocketHandler;
import net.findzach.nojokepanel.model.PanelContainer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

@Slf4j
public class BuildExecutor {

    private final PanelContainer panelContainer;
    private final BuildLogWebSocketHandler webSocketHandler;

    public BuildExecutor(PanelContainer panelContainer, BuildLogWebSocketHandler webSocketHandler) {
        this.panelContainer = panelContainer;
        this.webSocketHandler = webSocketHandler;
    }

    public void executeBuild() {
        String tempDir = "/tmp/" + panelContainer.getId();
        try {
            new File(tempDir).mkdir();

            cloneRepoWithToken(panelContainer.getImageName(), panelContainer.getDomain(), tempDir);
            Path packPath = downloadAndInstallPack(tempDir);
            buildWithPaketo(packPath.toString(), tempDir, panelContainer.getImageName(), panelContainer);

            webSocketHandler.broadcastMessage(panelContainer.getId(), "Build completed successfully!");
        } catch (Exception e) {
            webSocketHandler.broadcastMessage(panelContainer.getId(), "Build failed: " + e.getMessage());
            log.error("Build execution failed", e);
        } finally {
            File dir = new File(tempDir);
            if (dir.exists()) {
                deleteDirectory(dir);
            }
        }
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
                .addArgument("paketobuildpacks/builder-jammy-base");
        DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory(new File(cloneDir));
        WebSocketStreamHandler streamHandler = new WebSocketStreamHandler(panelContainer, webSocketHandler);
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