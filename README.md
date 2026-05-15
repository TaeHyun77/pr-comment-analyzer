## pr-comment-analyzer

자신의 GitHub PR에 달린 코멘트를 AI가 자동으로 분석해 Slack으로 알림을 전송하는 시스템<br><br>

**흐름** 

PR 코멘트 → GitHub Webhook → AI(사용자 지정) 분석 → Slack Incoming Webhook<br><br>

### 동작 방식

1. 내가 author인 PR의 코드 라인(또는 PR 일반)에 코멘트가 달리면 GitHub Webhook이 이 앱의 `/webhook/github`로 이벤트를 보냅니다.
   
2. `X-Hub-Signature-256` HMAC 서명을 검증하고, 내 PR + 비-봇 코멘트만 골라냅니다. (`include-own-comments=true`면 내 코멘트도 포함하도록 함)
   
3. 코멘트와 관련 코드를 AI LLM에 보내 "코멘트 요약 / 현재 vs 제안 방식 / 판정 / 근거 / 답변 초안"을 받습니다.
   
4. 결과를 Slack Incoming Webhook으로 전송합니다. (`SLACK_ENABLED=false`면 로그에만 출력)
   
5. 처리한 코멘트 ID는 `pr-analyzer-state.json`에 저장되어, 웹훅 재배달 시 중복 처리하지 않습니다.<br><br>

### 사전 준비

- 공개 HTTPS 엔드포인트 : GitHub가 접근할 수 있어야 합니다. 개발용은 `ngrok http 8080`, 상시 운영은 배포.
  
- 대상 레포 admin 권한 : Repository Webhook 등록에 필요
  
- AI API 키
  
- Slack Incoming Webhook URL : 알림을 받을 채널/DM에 연결된 것<br><br>

### 환경 변수

- `GROQ_API_KEY` : Groq API 키
  
- `GITHUB_WEBHOOK_SECRET` : GitHub 웹훅 등록 화면의 Secret과 동일한 임의 난수 값
  
- `GITHUB_LOGIN` : 분석 대상 GitHub 로그인 (해당 사용자가 author인 PR만 분석)
  
- `SLACK_WEBHOOK_URL` : Slack Incoming Webhook URL (`SLACK_ENABLED=true`일 때 필수)
  
- `SLACK_ENABLED` : Slack 전송 활성화 여부 (기본값 `true`, `false`면 로그만 출력)
  
- `GITHUB_TOKEN` : GitHub PAT (`Pull requests`, `Contents` Read 권한 권장). 비워두면 payload 데이터만 사용
  
- `GROQ_MODEL` : 사용할 Groq 모델
  
- `PR_ANALYZER_INCLUDE_OWN_COMMENTS` : 본인 PR 코멘트 포함 여부 (기본값 `true`)
  
- `PR_ANALYZER_STATE_FILE` : 상태 저장 파일 경로 (기본값 `./pr-analyzer-state.json`)

> 시크릿 값은 코드나 저장소에 커밋하지 말고 반드시 환경 변수로만 주입해야 합니다.

<br><br>

### 실행

```
./gradlew bootRun # 8080 포트

# 별도 터미널
ngrok http 8080 # 공개 URL 확보
```
<br>

### GitHub 웹훅 등록 (대상 레포마다 1회)

레포 → Settings → Webhooks → Add webhook

- Payload URL: `https://<공개URL>/webhook/github`
  
- Content type: `application/json`
  
- Secret: `GITHUB_WEBHOOK_SECRET`와 동일 값
  
- Events: "Let me select individual events" → Pull request review comments, Issue comments
  
- Add webhook 후 "Recent Deliveries"에서 ping 이벤트가 `2xx`인지 확인<br><br>

### 결과

<img width="1231" height="338" alt="ㅁㅁㅁ" src="https://github.com/user-attachments/assets/3de10a6b-85a2-4425-bf6b-c7edcac1b673" />
