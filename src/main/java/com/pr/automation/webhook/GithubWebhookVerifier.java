package com.pr.automation.webhook;

import com.pr.automation.common.error.AutomationException;
import com.pr.automation.common.error.ErrorCode;
import com.pr.automation.config.GithubProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

// 요청이 진짜 GitHub에서 온 것인지 서명으로 검증하는 클래스
@Component
@RequiredArgsConstructor
public class GithubWebhookVerifier {
    private static final String PREFIX = "sha256=";
    private final GithubProperties githubProperties;

    // 시크릿 미설정이면 서버 오류로 예외, 서명 헤더가 없거나 "sha256=" 접두사가 아니면 401 예외를 던짐
    // 서버가 직접 계산한 HMAC 값과 헤더에 담긴 값을 비교하여 다르면 401 반환
    public void verify(byte[] rawBody, String signatureHeader) {
        if (!StringUtils.hasText(githubProperties.getWebhookSecret())) {
            throw new AutomationException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.WEBHOOK_SIGNATURE_INVALID, "github.webhook-secret 미설정");
        }
        if (!StringUtils.hasText(signatureHeader) || !signatureHeader.startsWith(PREFIX)) {
            throw new AutomationException(HttpStatus.UNAUTHORIZED, ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }

        byte[] expected = hmacSha256(rawBody, githubProperties.getWebhookSecret());
        byte[] provided = hexToBytes(signatureHeader.substring(PREFIX.length()));
        if (provided == null || !MessageDigest.isEqual(expected, provided)) {
            throw new AutomationException(HttpStatus.UNAUTHORIZED, ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
    }

    // HmacSHA256 알고리즘으로 raw 바디의 HMAC 서명을 계산
    private static byte[] hmacSha256(byte[] data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 계산 실패", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
