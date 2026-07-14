# 시스템 전체 구조

## 전체적인 흐름

```text
GitHub
   │
   ▼
GithubWebhookController
   │
   ▼
WebhookEventHandler
   ├──────────────────────────────────────────────┐
   │                                              │
   ▼                                              ▼
CommentAnalysisService                      PrReviewService
   │                                              │
   ├── GroqChatClient (LLM) ◄─────────────────────┘
   │
   ├── GithubClient
   └── RepoFileReader


DeliveryStore
   ▲                         ▲                          ▲
   │ ACK 기록                │ ACK 기록                  │ isReceived() 조회
   │                         │                          │
CommentAnalysisService   PrReviewService     WebhookRedeliveryRecoverer / CoverageReporter
                                                          ▲
                                                          │ 수동 트리거
                                                RecoveryAdminController
```


# analysis 패키지

## 역할

PR 리뷰 코멘트를 LLM이 이해할 수 있는 형태로 변환하고, 도구 호출을 포함한 에이전트 루프를 수행하여 최종 분석 결과를 생성합니다.
LLM과의 대화, 프롬프트 생성, 도구 호출, 결과 파싱, 중복 처리 등 PR 코멘트 자동 분석 기능의 핵심 로직이 이 패키지에 모여 있습니다.
---

## 처리 흐름

`CommentAnalysisAgent.run()`은 한 번의 호출로 끝나지 않고, 최대 `maxRounds`회까지 LLM 호출을 반복하는 루프입니다. 도구(read_file/list_directory)를 쓴 라운드의 결과도 대화 이력에 누적된 뒤 다시 LLM에게 판단을 묻고, LLM이 submit_analysis를 호출해야(또는 예산 소진으로 강제되어야) 루프가 끝납니다.

```text
CommentEvent (Webhook)
        │
        ▼
buildContext()
        │
        ▼
CommentContext
        │
        ▼
AgentPromptBuilder.buildInitial()
        │
        ▼
Prompt 생성
        │
        ▼
   ┌────────────────────────────────────┐
   │                                    │
   ▼                                    │
GroqChatClient.send()  ── (매 라운드 반복) ─┘
   │
   ├─ read_file (RepoFileReader)       ──┐
   ├─ list_directory (RepoFileReader)  ──┤ 결과를 대화 이력에 추가 → 다음 라운드로
   │                                     │
   └─ submit_analysis 호출 ── 루프 종료 ──▶ AnalysisResult
```


# github 패키지

## 역할

GitHub REST API와의 모든 통신을 담당합니다.

- PR 정보 조회
- 변경 파일 조회
- 코멘트 조회
- 파일 내용 조회
- PR 리뷰 작성
- Delivery 재전송

GitHub 토큰이 없으면 자동으로 비활성화되며, 조회 실패는 가능한 한 예외 대신 빈 값을 반환하여 분석 파이프라인이 중단되지 않도록 설계되어 있습니다. 반면 PR 코멘트 게시처럼 호출자가 실패를 반드시 인지해야 하는 쓰기 작업은 예외를 던집니다.

---

## 구성

```text
GithubClient
    ▲
    ├── CommentAnalysisService
    │      ├─ Head SHA 조회
    │      ├─ 부모 코멘트 조회
    │      └─ Patch 조회
    │
    ├── GithubRepoFileReader
    │      ├─ read_file
    │      └─ list_directory
    │
    └── PrReviewService
           ├─ 변경 파일 조회
           └─ PR 코멘트 작성


GithubDeliveryClient
    ▲
    ├── WebhookRedeliveryRecoverer
    └── CoverageReporter


RepoFileReader (Interface)
        ▲
        │
GithubRepoFileReader
```


# review 패키지

## 역할

PR이 생성되면 변경된 코드를 대상으로 4단계 AI 코드 리뷰를 수행합니다.

분석 대상은 PR 전체이며, 리뷰 결과를 Markdown으로 생성하여 GitHub PR 코멘트로 게시합니다. LLM 호출은 PR당 정확히 4번(언어 → 프레임워크/인프라 → 도메인/보안 → 최종검증) 발생합니다.

---

## 처리 흐름

4단계는 병렬이 아니라 완전히 순차적으로 실행됩니다. 4단계(최종검증)는 1~3단계의 결과를 입력으로 받아야만 실행될 수 있기 때문에, 앞 단계가 끝나야 다음 단계가 시작됩니다.

```text
PrReviewEvent
      ▼
PrReviewService.review()
      ▼
GithubClient.fetchPullFiles()
      ▼
PrReviewPipeline.run()
      ▼
1단계 (언어)
  PrReviewPromptBuilder.buildStagePrompt() → GroqChatClient.send() → submit_findings
      │                                                                    ▼
      │                                                          StageReviewResult
      ▼
2단계 (프레임워크/인프라)
  PrReviewPromptBuilder.buildStagePrompt() → GroqChatClient.send() → submit_findings
      │                                                                    ▼
      │                                                          StageReviewResult
      ▼
3단계 (도메인/보안)
  PrReviewPromptBuilder.buildStagePrompt() → GroqChatClient.send() → submit_findings
      │                                                                    ▼
      │                                                          StageReviewResult
      ▼ (1~3단계 결과 전체를 입력으로)
4단계 (최종검증)
  PrReviewPromptBuilder.buildFinalPrompt() → GroqChatClient.send() → submit_review
      ▼
PrReviewResult
      ▼
PrReviewCommentFormatter.format()
      ▼
Markdown 생성
      ▼
GithubClient.createIssueComment()
```


# webhook 패키지

## 역할

GitHub Webhook을 안전하게 수신하고, 이벤트를 적절한 서비스로 전달하며, 처리 실패 또는 누락된 Webhook을 복구합니다.

- Webhook 서명 검증
- 이벤트 필터링
- 서비스 라우팅
- ACK 기록
- Delivery 재전송
- 누락 진단

---

## 처리 흐름

`WebhookEventHandler.handle()`은 `pull_request_review_comment`와 `issue_comment` 두 이벤트를 모두 동일한 코멘트 분석 경로로 보냅니다

```text
GitHub
   │
   ▼
GithubWebhookController.receive()
   │
   ▼
GithubWebhookVerifier.verify()
   │
   ▼
WebhookEventHandler.handle()
   │
   ├─────────────────────────────────────────────────┐
   ▼                     ▼                            ▼
 ping             pull_request            pull_request_review_comment
   │                     │                     / issue_comment
   ▼                     ▼                            │
ACK 기록     PrReviewService.reviewAsync()             ▼
                         │                CommentAnalysisService.analyzeAsync()
                         ▼                             │
                     ACK 기록                           ▼
                                                     ACK 기록
```


## 실패 및 누락 복구

```text
DeliveryStore
      ▲
      │
      ├─────────────────────────────┐
      │                             │
      ▼                             ▼
WebhookRedeliveryRecoverer    CoverageReporter
      │                             │
      └──────────────┬──────────────┘
                     ▼
       RecoveryAdminController
             (수동 재전송 / 커버리지 조회)
```

- `WebhookRedeliveryRecoverer` : 부팅 시 1회 + 주기적 스케줄(기본 30분 간격)로, `DeliveryStore`에 ACK 기록이 없는 delivery를 찾아 GitHub API로 재전송을 요청합니다.
- `CoverageReporter`: 실제 발생한 코멘트 이벤트 수 대비 ACK된 수를 비교해 누락 현황을 진단합니다 - ex ) 19/20건 처리
- `RecoveryAdminController`: 위 두 기능을 운영자가 수동으로 트리거할 수 있는 내부 관리용 HTTP 엔드포인트를 제공합니다.