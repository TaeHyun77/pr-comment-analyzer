package com.pr.automation.github;

import com.pr.automation.config.properties.GithubProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 체크아웃 생성 지점을 빈으로 감싸 서비스가 정적 호출과 토큰 설정에 직접 묶이지 않게 한다
@Component
@RequiredArgsConstructor
public class RepoCheckoutFactory {

    private final GithubProperties githubProperties;

    public RepoCheckout checkout(String repoFullName, String headSha) {
        return RepoCheckout.fetch(repoFullName, headSha, githubProperties.getToken());
    }
}
