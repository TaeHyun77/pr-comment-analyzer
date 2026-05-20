# 아키텍처

## 개요

자신의 GitHub PR에 달린 코멘트를 받아 LLM으로 분석하고 결과를 Slack으로 보내는 시스템.

흐름: PR 코멘트 → GitHub Webhook → 서명 검증 → 필터링 → (비동기) LLM 자율 탐색 분석 → Slack Incoming Webhook

LLM 분석은 단발 호출이 아니라, LLM이 도구로 레포 파일을 스스로 조회하며 코멘트가 가리키는 코드의
함수 호출·설정 파일을 추적하는 에이전트 루프(폴백 없음. 레포 조회 불가 시 분석 실패).

- 트리거: GitHub Repository Webhook 
- LLM: Groq (OpenAI 호환 Chat Completions API), `RestTemplate`로 직접 호출
- 통지: Slack Incoming Webhook 
- 기술 스택: Spring Boot 2.7.18 / Java 8 / Jackson 3

## 트리거와 분석의 분리

`CommentAnalysisService`(코멘트 → 분석 → Slack)는 트리거와 무관하게 재사용된다. 현재 트리거는 웹훅 하나지만,
나중에 폴링 폴러를 추가해도 같은 서비스를 호출하면 된다.

## 자율 탐색 에이전트 루프

`GroqClient.analyze(context, reader)`는 폴백 없이 항상 에이전트 루프로 동작한다. reader는 필수다.

- **reader 결정** (`CommentAnalysisService.repoFileReader`): GitHub 토큰이 있어야 하고,
  `headSha`가 있으면 그대로, 없으면(issue_comment 등) GitHub API로 PR head SHA를 조회한다.
  토큰이 없거나 head SHA를 끝내 확보하지 못하면 `REPO_NOT_READABLE` 예외(분석 중단).
- **에이전트 루프**: LLM에 `read_file`·`list_directory`·`submit_analysis` 도구를 노출하고,
  LLM이 코멘트가 가리키는 코드의 함수 정의·설정 파일을 스스로 조회하다 `submit_analysis`
  호출로 종료한다.
  - 레포/ref는 서버가 `headSha`로 고정(LLM은 경로만 지정) → 읽기 전용·단일 커밋 스코프.
  - 결과 스키마는 `submit_analysis` 도구의 파라미터로 강제(휴리스틱 JSON 파싱 제거).
  - 예산: `maxToolIterations`(라운드)·`maxFiles`·`maxFileChars`. 소진 시 도구를
    `submit_analysis`만 남겨 강제 마무리, 그래도 미제출이면 `AI_RESPONSE_PARSE_ERROR`.
    `maxToolIterations<=0`은 오설정으로 보고 진입 전 즉시 실패(`AI_API_ERROR`).
  - 파일 조회 실패는 예외가 아닌 도구 결과 메시지로 회신해 LLM이 경로를 바꿔 재시도.

## 동기/비동기 경계

- 웹훅 컨트롤러: 서명 검증 + 빠른 필터(이벤트 타입/action/PR 작성자/봇)만 동기로 하고 즉시 200 반환.
- 분석(LLM 호출): `@Async("analysisExecutor")`로 분리. 비동기 스레드 예외는 `@RestControllerAdvice`가 못 잡으므로
  `CommentAnalysisService` 내부에서 try-catch 후 로그 + Slack 실패 알림.

## 외부 호출 시 주의

- Spring Boot 4는 Jackson 3을 사용한다(`tools.jackson.databind.ObjectMapper`, 어노테이션은 `com.fasterxml.jackson.annotation.*` 유지).
- 외부 API DTO는 전역 네이밍 전략에 의존하지 않고 `@JsonProperty`로 snake_case를 명시한다(GitHub payload, Groq 요청, AnalysisResult).
- `RestClient`는 `RestClient.builder()`로 직접 생성한다(이 프로젝트에는 `RestClient.Builder` 자동 구성 빈이 없다).
- GitHub contents API처럼 경로에 `/`가 들어가는 호출은 `owner`/`repo`를 URI 변수로 따로 넘긴다(`{repo}`에 `owner/repo`를 넣으면 `/`가 인코딩됨).
