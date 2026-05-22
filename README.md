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

GitHub 웹훅 이벤트 발송 → ngrok 터널 → SpringBoot 수신 ( HMAC 서명 검증 + 이벤트 타입 1차 필터 ) → JSON 페이로드 파싱 + 2차 필터 (생성, PR, 봇 ) → 비동기 분석 디스패치 → LLM 에이전트 루프 → Slack 알림 ( 판정 및 답변 초안 )<br><br>


### 결과
---

<img width="1231" height="338" alt="ㅁㅁㅁ" src="https://github.com/user-attachments/assets/3de10a6b-85a2-4425-bf6b-c7edcac1b673" />
