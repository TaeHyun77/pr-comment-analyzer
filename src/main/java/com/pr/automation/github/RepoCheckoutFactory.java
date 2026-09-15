package com.pr.automation.github;

import com.pr.automation.config.properties.GithubProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RepoCheckoutFactory {
    private final GithubProperties githubProperties;

    public RepoCheckout checkout(String repoFullName, String headSha) {
        return RepoCheckout.fetch(repoFullName, headSha, githubProperties.getToken());
    }
}
