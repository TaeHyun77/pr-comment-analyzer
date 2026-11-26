AI를 활용한 PR 리뷰 코멘트 분석을 자동화하여 코드 리뷰 과정의 반복 작업을 줄이는 시스템입니다. 분석 결과 재사용을 통한 LLM 호출 및 토큰 사용량 감소와 정확한 분석에 중점을 두었습니다.

PR에 코멘트가 달리면, AI가 해당 코멘트를 분석하여 요약, 현재 방식과 제안 방식의 비교, 판정, 근거, 답변 초안을 생성하고 이를 Slack으로 전송하여 리뷰 코멘트의 핵심 내용을 빠르게 파악할 수 있도록 합니다.<br><br>

> 개발 블로그 : [https://velog.io/@ayeah77/series/PR-리뷰-코멘트](https://velog.io/@ayeah77/series/PR-%EB%A6%AC%EB%B7%B0-%EC%BD%94%EB%A9%98%ED%8A%B8)

<br>

### 기술 스택
---
Backend : Java, Spring Boot, Spring Web MVC

AI : Claude Code

Database : MySQL

ETC : GitHub Repository Webhook, Slack Incoming Webhook<br><br>

### 아키텍처
---
<img width="1059" height="381" alt="image" src="https://github.com/user-attachments/assets/535a2673-ee5c-4803-a617-07260e392a0f" /><br><br>

### PR COMMENT 분석 과정

1. 웹훅 수신 : GitHub 웹훅 이벤트 수신
   
2. 비동기 분석 실행
   
3. 코드 체크아웃 : PR의 head 커밋을 임시 폴더에 체크아웃
   
4. Claude 프로세스 실행 : `claude -p`로 별도 프로세스 생성
    - 읽기 전용 도구 (`Read`, `Grep`, `Glob`)만 허용
    - 개인 설정, 플러그인, MCP 차단
    - 세션 기록 비활성화
    - 시스템 프롬프트로 분석 지시 전달
      
5. 코드 분석 : Claude가 직접 파일을 탐색하고 분석한 뒤 JSON 결과 반환
   
6. 결과 처리 : JSON 파싱 및 DB 저장
    
7. 임시 파일 정리 : 체크아웃한 임시 폴더 삭제
    
8. Slack 알림 : 분석 결과 전송
