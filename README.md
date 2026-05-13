# pr-comment-analyzer

자신의 GitHub PR에 달린 코멘트를 AI가 자동으로 분석해 Slack으로 보내주는 시스템.

**흐름:** PR 코멘트 → GitHub Webhook → AI(Groq) 분석 → Slack Incoming Webhook

## 동작 방식

1. 내가 author인 PR의 코드 라인(또는 PR 일반)에 코멘트가 달리면 GitHub Webhook이 이 앱의 `/webhook/github`로 이벤트를 보낸다.
2. `X-Hub-Signature-256` HMAC 서명을 검증하고, 내 PR + 비-봇 코멘트만 골라낸다(`include-own-comments=true`면 내 코멘트도 포함).
3. 코멘트와 관련 코드(diff)를 Groq LLM에 보내 "코멘트 요약 / 현재 vs 제안 방식 / 판정 / 근거 / 답변 초안"을 받는다.
4. 결과를 Slack Incoming Webhook으로 전송한다(`SLACK_ENABLED=false`면 로그에만 출력).
5. 처리한 코멘트 ID는 `pr-analyzer-state.json`에 저장돼 웹훅 재배달 시 중복 처리하지 않는다.

## 사전 준비

- **공개 HTTPS 엔드포인트**: GitHub가 접근할 수 있어야 한다. 개발용은 `ngrok http 8080`, 상시 운영은 배포.
- **대상 레포 admin 권한**: Repository Webhook 등록에 필요.
- **Groq API 키**: <https://console.groq.com>
- **Slack Incoming Webhook URL**: 알림을 받을 채널/DM에 연결된 것.

## 환경 변수

| 변수 | 필수 | 설명 |
|---|---|---|
| `GROQ_API_KEY` | ✅ | Groq API 키 |
| `GITHUB_WEBHOOK_SECRET` | ✅ | 임의 난수. GitHub 웹훅 등록 화면의 Secret과 동일 값 |
| `GITHUB_LOGIN` | ✅ | 내 GitHub 로그인 (이 사용자가 author인 PR만 분석) |
| `SLACK_WEBHOOK_URL` | `SLACK_ENABLED=true`면 ✅ | Slack Incoming Webhook URL |
| `SLACK_ENABLED` | — | 기본 `true`. `false`면 전송 없이 로그만 |
| `GITHUB_TOKEN` | — | PAT(Pull requests/Contents Read). 비우면 payload 데이터만으로 동작 |
| `GROQ_MODEL` | — | 기본 `llama-3.3-70b-versatile` |
| `PR_ANALYZER_INCLUDE_OWN_COMMENTS` | — | 기본 `true` |
| `PR_ANALYZER_STATE_FILE` | — | 기본 `./pr-analyzer-state.json` |

> 시크릿은 코드/저장소에 커밋하지 말고 환경 변수로만 주입할 것.

## 실행

```bash
export GROQ_API_KEY=...
export GITHUB_WEBHOOK_SECRET=...
export GITHUB_LOGIN=...
export SLACK_WEBHOOK_URL=...
./gradlew bootRun        # 8080 포트
# 별도 터미널
ngrok http 8080          # 공개 URL 확보
```

## GitHub 웹훅 등록 (대상 레포마다 1회)

레포 → Settings → Webhooks → Add webhook

- Payload URL: `https://<공개URL>/webhook/github`
- Content type: `application/json`
- Secret: `GITHUB_WEBHOOK_SECRET`와 동일 값
- Events: "Let me select individual events" → ☑ Pull request review comments, ☑ Issue comments
- Add webhook 후 "Recent Deliveries"에서 ping 이벤트가 `2xx`인지 확인

## 엔드포인트

- `POST /webhook/github` — GitHub 웹훅 수신 (서명 검증)
- `GET /health` — 헬스 체크
- `POST /internal/analyze` — 수동 테스트. 헤더 `X-GitHub-Event`(기본 `pull_request_review_comment`) + 웹훅 형식 JSON 바디를 받아 동기 분석 후 결과를 반환 (서명 검증·중복 기록 없음)

## 테스트

```bash
./gradlew test
```
