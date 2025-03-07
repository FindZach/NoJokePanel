package net.findzach.nojokepanel.service;

import net.findzach.nojokepanel.model.GitHubDeploy;
import net.findzach.nojokepanel.model.PanelContainer;

import java.util.Map;

/**
 * @author Zach Smith
 * @since 3/6/2025
 */
public interface ContainerServiceInterface {
    Map<String, PanelContainer> getContainers();
    PanelContainer getContainer(String id);
    PanelContainer deployGitHubRepo(GitHubDeploy githubDeploy) throws Exception;
    PanelContainer stopContainer(String id) throws Exception;
    PanelContainer startContainer(String id) throws Exception;
    PanelContainer restartContainer(String id) throws Exception;
    void removeContainer(String id) throws Exception;
    void streamBuildLogs(PanelContainer panelContainer);
    PanelContainer initiateDeployment(GitHubDeploy githubDeploy);
}
