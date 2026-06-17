## PR & PR COMMENT ANALYZER

자신의 GitHub PR을 AI가 자동으로 분석해주는 시스템

① PR을 생성하면 언어→프레임워크/인프라→도메인/보안→최종검증 4단계 리뷰를 돌려 기계적 이슈를 먼저 정리한 코멘트를 PR에 게시합니다.

② PR 코멘트가 달리면 요약/현재 방식과 제안 방식 비교/판정/근거/답변 초안을 Slack으로 전송하여 사용자가 코멘트에 대한 분석을 보다 쉽도록 해줍니다.<br><br>

> 블로그 : [https://velog.io/@ayeah77/series/PR-리뷰-코멘트](https://velog.io/@ayeah77/series/PR-%EB%A6%AC%EB%B7%B0-%EC%BD%94%EB%A9%98%ED%8A%B8)

<br>

## 기술 스택

BackEnd : Spring Boot 2.7, Java 8, Gradle

LLM : Groq ( 비용 이슈 .. 사용자에 따라 변경 가능 )

Integration : GitHub Repository Webhook & REST API

Notification : Slack Incoming Webhook<br><br>

## 동작 흐름
---

### 1. PR 코멘트 분석

GitHub 웹훅 이벤트 발송 → ngrok 터널 → SpringBoot 수신 ( HMAC 서명 검증 + 이벤트 타입 1차 필터 ) → JSON 페이로드 파싱 + 2차 필터 (생성, PR, 봇 ) → 비동기 분석 디스패치 → LLM 에이전트 루프 → Slack 알림 ( 판정 및 답변 초안 )<br><br>

### 2. PR 생성 시 4단계 자동 리뷰
  
PR을 생성하면 언어 → 프레임워크/인프라 → 도메인/보안 → 최종검증의 4단계 순차 리뷰가 자동으로 진행됩니다. 예외처리 누락/null-safety 같은 기계적 이슈를 먼저 잡아 GitHub PR 코멘트로 정리해 게시하므로, 사람 리뷰어는 그게 정리된 상태에서 로직이 맞는지, 이 트레이드오프를 받아들일 만한가 같은 본질적 판단에만 집중할 수 있습니다.

GitHub 웹훅 → 수신/서명 검증 → 내 PR/봇 필터 → 비동기 리뷰 디스패치 → 4단계 순차 LLM 호출(단계마다 하나의 관심사) → 최종검증 단계가 중복/오탐 제거 + 리뷰어 집중 포인트 도출 → PR 코멘트 게시<br><br>

- 트리거 : 내가 연 PR의 `opened` 이벤트 ( 봇/타인 PR 제외 ) - GitHub 웹훅에서 Pull requests 이벤트 구독 필요
- PR 코멘트 게시는 토큰 쓰기 권한 필요 ( classic PAT의 `repo` 스코프 또는 fine-grained의 Pull requests 쓰기 )<br><br>

#### 커리큘럼 : Claude Code로 PR을 세부적으로 나누기

PR이 너무 많은 주제를 담으면 분석 초점이 흐려지기 떄문에, PR을 올리기 전에 Claude Code로 큰 변경을 주제별 PR로 나누면 단계별 리뷰 품질과 비용 모두 개선됩니다. ( 자동화가 아닌, 사람이 따르는 사전 절차 )

1. 변경 범위 파악 : `git diff` / `git status`로 이번 작업이 건드린 영역을 훑는다.
2. 주제 식별 : Claude Code에 diff를 주고 독립적으로 리뷰/머지 가능한 논리적 단위로 묶게 한다.
3. 분할 계획 수립 : 각 단위를 어떤 순서의 PR로 낼지, 의존 관계는 무엇인지 정리한다. 
4. 단위별 브랜치/커밋 분리 : 계획에 따라 변경을 브랜치/커밋으로 쪼갠다.
5. 작은 PR 순차 생성 — 단위별로 PR을 만든다. 각 PR이 생성되면 위 4단계 자동 리뷰가 작은 범위에 대해 정확히 동작한다.<br><br>


### 결과
---

<img width="1231" height="338" alt="ㅁㅁㅁ" src="https://github.com/user-attachments/assets/3de10a6b-85a2-4425-bf6b-c7edcac1b673" />

### 개선점
---
-  AI 및 외부 API 호출 시 복구 메커니즘 부재 해결
-  분석 진행 중 같은 코멘트의 중복 분석
-  Actionable Slack 알림
-  Webhook Redelivery 복구 메커니즘 도입
