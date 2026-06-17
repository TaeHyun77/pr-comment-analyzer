## pr-comment-analyzer

자신의 GitHub PR에 달린 코멘트를 AI가 자동으로 분석해 요약, 현재 방식과 제안 방식 비교, 판정, 근거, 답변 초안을 Slack으로 전송해주는 시스템<br><br>

> 블로그 : [https://velog.io/@ayeah77/series/PR-리뷰-코멘트](https://velog.io/@ayeah77/series/PR-%EB%A6%AC%EB%B7%B0-%EC%BD%94%EB%A9%98%ED%8A%B8)

<br>

## 기술 스택

BackEnd : Spring Boot 2.7, Java 8, Gradle

LLM : Groq ( 사용자에 따라 변경 가능 )

Integration : GitHub Repository Webhook & REST API

Notification : Slack Incoming Webhook<br><br>

## 동작 흐름

### 1. PR 코멘트 분석 (기존)

GitHub 웹훅 이벤트 발송 → ngrok 터널 → SpringBoot 수신 ( HMAC 서명 검증 + 이벤트 타입 1차 필터 ) → JSON 페이로드 파싱 + 2차 필터 (생성, PR, 봇 ) → 비동기 분석 디스패치 → LLM 에이전트 루프 → Slack 알림 ( 판정 및 답변 초안 )<br><br>

### 2. PR 생성 시 4단계 자동 리뷰 (신규)

PR을 생성(`pull_request` opened)하면, **언어 → 프레임워크/인프라 → 도메인/보안 → 최종검증**의 4단계 순차 리뷰가 자동으로 돌아간다. 예외처리 누락·null-safety 같은 **기계적 이슈를 먼저** 잡아 GitHub PR 코멘트로 정리해 게시한다. 사람 리뷰어는 그게 정리된 상태에서 "이 로직이 맞나, 이 트레이드오프를 받아들일 만한가" 같은 **본질적 판단**에만 집중할 수 있다.

- 트리거: 내가 연 PR의 `opened` 이벤트 (봇/타인 PR 제외) — GitHub 웹훅 설정에서 **Pull requests** 이벤트를 구독해야 동작한다 (기존 코멘트 분석은 Issue/PR review 코멘트 이벤트 구독)
- 단계마다 1회 LLM 호출(각 단계는 하나의 관심사에만 집중) → 최종검증 단계가 중복·오탐을 제거하고 리뷰어 집중 포인트를 도출
- 결과는 PR 코멘트로 게시 ( `PR_REVIEW_POST_TO_SLACK=true`면 Slack에도 보조 전송 )
- PR 코멘트 게시는 **쓰기 권한**이 필요하다. classic PAT의 `repo` 스코프면 코멘트 쓰기가 포함되고, fine-grained 토큰이면 Pull requests(또는 Issues) 쓰기 권한이 있어야 한다. 또한 4단계 응답을 담으려면 `GROQ_MAX_TOKENS`를 충분히 확보하는 것이 좋다.

> 큰 PR은 분석 초점이 흐려진다. PR을 올리기 **전에** Claude Code로 변경을 주제별 PR로 나누면(아래 커리큘럼) 단계별 리뷰 품질과 비용 모두 개선된다.

#### 커리큘럼: Claude Code로 PR을 세부적으로 나누기

PR을 생성하기 전에, 하나의 큰 변경을 리뷰 가능한 단위로 분할하는 사전 절차다. (자동화가 아닌 사람이 따르는 워크플로)

1. **변경 범위 파악** — `git diff`/`git status`로 이번 작업이 건드린 영역을 훑는다.
2. **주제 식별** — Claude Code에 diff를 주고 "독립적으로 리뷰·머지 가능한 논리적 단위"로 묶게 한다. (예: 리팩터링 / 기능 추가 / 설정 변경 / 테스트)
3. **분할 계획 수립** — 각 단위를 어떤 순서의 PR로 낼지, 의존 관계는 무엇인지 정리한다. (선행 PR → 후속 PR)
4. **단위별 브랜치/커밋 분리** — 계획에 따라 변경을 브랜치/커밋으로 쪼갠다.
5. **작은 PR 순차 생성** — 단위별로 PR을 만든다. 각 PR이 생성되면 위 4단계 자동 리뷰가 작은 범위에 대해 정확히 동작한다.


### 결과
---

<img width="1231" height="338" alt="ㅁㅁㅁ" src="https://github.com/user-attachments/assets/3de10a6b-85a2-4425-bf6b-c7edcac1b673" />
