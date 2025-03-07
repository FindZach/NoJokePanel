package net.findzach.nojokepanel.controller;

import net.findzach.nojokepanel.model.GitHubDeploy;
import net.findzach.nojokepanel.model.PanelContainer;
import net.findzach.nojokepanel.service.ContainerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/")
@Slf4j
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
    @ResponseBody // Return JSON instead of a template
    public Map<String, String> deployGitHubRepo(@ModelAttribute GitHubDeploy githubDeploy) {
        try {
            PanelContainer panelContainer = containerService.deployGitHubRepo(githubDeploy);
            Map<String, String> response = new HashMap<>();
            response.put("containerId", panelContainer.getId());
            response.put("message", "GitHub repo deployed successfully. Access at https://" + githubDeploy.getDomain());
            return response;
        } catch (Exception e) {
            log.error("GitHub deployment failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Deployment failed: " + e.getMessage());
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