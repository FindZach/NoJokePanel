package net.findzach.nojokepanel.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class GitHubDeploy {
    // Getters and setters
    private String repoUrl;
    private String githubToken;
    private String domain;
    private int internalPort;

}