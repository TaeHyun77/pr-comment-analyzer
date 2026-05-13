# 아키텍처

## 개요

자신의 GitHub PR에 달린 코멘트를 받아 LLM으로 분석하고 결과를 Slack으로 보내는 시스템.

흐름: PR 코멘트 → GitHub Webhook → 서명 검증 → 필터링 → (비동기) LLM 분석 → Slack Incoming Webhook

- 트리거: GitHub Repository Webhook 
- LLM: Groq (OpenAI 호환 Chat Completions API), `RestClient`로 직접 호출
- 통지: Slack Incoming Webhook 
- 기술 스택: Spring Boot 4.x / Java 8 / Jackson 3

## 패키지 구조 (`com.pr.automation`)

```
AutomationApplication            @SpringBootApplication, @ConfigurationPropertiesScan
config/
  GithubProperties               ("github")  token(옵션), login, webhookSecret
  GroqProperties                 ("groq")  apiKey, baseUrl, model, maxTokens, temperature, jsonMode
  SlackProperties                ("slack")  enabled, webhookUrl
  PrAnalyzerProperties           ("pr-analyzer")  includeOwnComments, stateFile, fileContextLines
  AsyncConfig                    @EnableAsync, 분석용 ThreadPoolTaskExecutor("analysisExecutor")
  RestClientConfig               groqRestClient / githubRestClient / slackRestClient
webhook/
  GithubWebhookController        POST /webhook/github : 서명 검증 → handler 위임 → 200
  GithubWebhookVerifier          X-Hub-Signature-256 HMAC-SHA256 검증
  WebhookEventHandler            payload 파싱·필터링 → CommentEvent 추출 → analyzeAsync 트리거
  dto/WebhookPayload             GitHub 페이로드 중 필요한 필드만 (@JsonProperty로 snake_case 매핑)
github/
  GithubClient                   GitHub REST API (토큰 없으면 비활성). 답글 부모 코멘트 조회 등
analysis/
  CommentAnalysisService         @Async 분석 파이프라인: dedup → 컨텍스트 구성 → Groq → Slack → 처리 기록
  GroqClient                     Groq Chat Completions 호출, 응답 → AnalysisResult 파싱(코드펜스 제거 폴백)
  ProcessedCommentStore          처리한 comment.id를 JSON 파일에 영속 (중복 처리 방지)
  dto/                           CommentEvent(추출 결과), CommentContext(LLM 입력), AnalysisResult(LLM 출력)
slack/
  SlackNotifier                  Incoming Webhook으로 Block Kit 메시지 POST
support/
  InternalController             GET /health, POST /internal/analyze (수동 테스트: 웹훅 형식 바디를 동기 분석)
common/error/
  AutomationException(HttpStatus, ErrorCode) / ErrorCode / ErrorDto / CustomExceptionHandler(@RestControllerAdvice)
```

## 트리거와 분석의 분리

`CommentAnalysisService`(코멘트 → 분석 → Slack)는 트리거와 무관하게 재사용된다. 현재 트리거는 웹훅 하나지만,
나중에 폴링 폴러를 추가해도 같은 서비스를 호출하면 된다.

## 동기/비동기 경계

- 웹훅 컨트롤러: 서명 검증 + 빠른 필터(이벤트 타입/action/PR 작성자/봇)만 동기로 하고 즉시 200 반환.
- 분석(LLM 호출): `@Async("analysisExecutor")`로 분리. 비동기 스레드 예외는 `@RestControllerAdvice`가 못 잡으므로
  `CommentAnalysisService` 내부에서 try-catch 후 로그 + Slack 실패 알림.

## 외부 호출 시 주의

- Spring Boot 4는 Jackson 3을 사용한다(`tools.jackson.databind.ObjectMapper`, 어노테이션은 `com.fasterxml.jackson.annotation.*` 유지).
- 외부 API DTO는 전역 네이밍 전략에 의존하지 않고 `@JsonProperty`로 snake_case를 명시한다(GitHub payload, Groq 요청, AnalysisResult).
- `RestClient`는 `RestClient.builder()`로 직접 생성한다(이 프로젝트에는 `RestClient.Builder` 자동 구성 빈이 없다).
- GitHub contents API처럼 경로에 `/`가 들어가는 호출은 `owner`/`repo`를 URI 변수로 따로 넘긴다(`{repo}`에 `owner/repo`를 넣으면 `/`가 인코딩됨).
