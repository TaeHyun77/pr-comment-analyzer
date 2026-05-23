package com.pr.automation.webhook;

import com.pr.automation.common.error.AutomationException;
import com.pr.automation.config.GithubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class GithubWebhookVerifierTest {

    // GitHub 공식 문서의 테스트 벡터
    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] PAYLOAD = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String VALID_SIGNATURE =
            "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    private GithubWebhookVerifier verifier(String secret) {
        return new GithubWebhookVerifier(new GithubProperties("token", "me", secret, null, null));
    }

    @Test
    void 올바른_서명이면_통과한다() {
        verifier(SECRET).verify(PAYLOAD, VALID_SIGNATURE);
    }

    @Test
    void 서명이_틀리면_401() {
        AutomationException ex = catchThrowableOfType(
                () -> verifier(SECRET).verify(PAYLOAD, "sha256=0000000000000000000000000000000000000000000000000000000000000000"),
                AutomationException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 서명_헤더가_없으면_401() {
        assertThatThrownBy(() -> verifier(SECRET).verify(PAYLOAD, null))
                .isInstanceOf(AutomationException.class);
        assertThatThrownBy(() -> verifier(SECRET).verify(PAYLOAD, "garbage"))
                .isInstanceOf(AutomationException.class);
    }

    @Test
    void 시크릿_미설정이면_500() {
        AutomationException ex = catchThrowableOfType(
                () -> verifier("").verify(PAYLOAD, VALID_SIGNATURE),
                AutomationException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
