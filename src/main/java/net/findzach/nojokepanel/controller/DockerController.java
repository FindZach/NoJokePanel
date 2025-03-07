package net.findzach.nojokepanel.controller;

import net.findzach.nojokepanel.model.GitHubDeploy;
import net.findzach.nojokepanel.model.PanelContainer;
import net.findzach.nojokepanel.service.ContainerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/")
@Slf4j
@EnableAsync
public class DockerController {

    private final ContainerService containerService;

    @Autowired
    public DockerController(ContainerService containerService) {
        this.containerService = containerService;
    }

    @GetMapping
    public String home(Model model) {
        model.addAttribute("containers", containerService.getContainers().values());
        return "index";
    }

    @GetMapping("/deploy")
    public String deployForm(Model model) {
        model.addAttribute("githubDeploy", new GitHubDeploy());
        return "deploy";
    }

    @PostMapping("/deploy")
    @ResponseBody
    public Map<String, String> deployGitHubRepo(@ModelAttribute GitHubDeploy githubDeploy) {
        log.info("Deploy request received: repoUrl={}, domain={}, internalPort={}",
                githubDeploy.getRepoUrl(), githubDeploy.getDomain(), githubDeploy.getInternalPort());
        try {
            PanelContainer panelContainer = containerService.initiateDeployment(githubDeploy); // New method
            Map<String, String> response = new HashMap<>();
            response.put("containerId", panelContainer.getId());
            response.put("message", "Deployment initiated. Streaming logs via WebSocket.");
            log.info("Deploy response: {}", response);
            // Start the build process asynchronously
            CompletableFuture.runAsync(() -> {
                try {
                    containerService.completeDeployment(panelContainer);
                } catch (Exception e) {
                    log.error("Async deployment failed for containerId {}: {}", panelContainer.getId(), e.getMessage(), e);
                }
            });
            return response;
        } catch (Exception e) {
            log.error("Deployment initiation failed: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Deployment initiation failed: " + e.getMessage(), e);
        }
    }

    @GetMapping("/container/{id}")
    public String containerDetails(@PathVariable String id, Model model) {
        PanelContainer panelContainer = containerService.getContainer(id);
        if (panelContainer == null) {
            model.addAttribute("error", "Container not found");
            return "error";
        }
        model.addAttribute("container", panelContainer);
        return "container";
    }

    @PostMapping("/container/{id}/stop")
    public String stopContainer(@PathVariable String id, Model model) {
        try {
            PanelContainer panelContainer = containerService.stopContainer(id);
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
            PanelContainer panelContainer = containerService.startContainer(id);
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
            PanelContainer panelContainer = containerService.restartContainer(id);
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
            containerService.removeContainer(id);
            model.addAttribute("message", "Container " + id + " removed");
            return "result";
        } catch (Exception e) {
            log.error("Remove container failed", e);
            model.addAttribute("error", "Failed: " + e.getMessage());
            return "error";
        }
    }
}