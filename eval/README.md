# 코멘트 분석 평가셋

정답 라벨이 있는 코멘트 30건으로 코멘트 분석의 **판정 정확도 / 근거 충실도 / 환각 / 탐색 효율 / 비용·시간**을 재기 위한 데이터셋입니다.
검증 단계 유무(A/B), 코드베이스 탐색 유무 같은 구성 비교의 공통 입력으로 씁니다.

## 구성

| 파일 | 역할 |
|---|---|
| `cases.json` | 케이스 30건 + 정답 라벨 + 근거 코드 위치 |
| `build_events.py` | 스키마/앵커 검증(`--check`), `CommentEvent` 리플레이 입력 생성(`--emit DIR`) |
| `run_eval.py` | 리플레이 러너. 프로덕션과 같은 방식으로 `claude` CLI를 호출해 결과를 `results/<label>.jsonl`에 저장 |
| `score.py` | 채점, 혼동행렬, 구성 비교(A/B), 근거 충실도 판정 |
| `prompts/verifier-addendum.md` | 검증 단계(별도 세션)의 시스템 프롬프트 추가분. 프로덕션 시스템 프롬프트 뒤에 붙는다 |
| `test_eval.py` | 러너/채점기 자체 테스트. 스텁 CLI라 비용이 들지 않는다 |

```bash
python3 eval/build_events.py --check          # 라벨 동결 전 필수
python3 -m unittest discover -s eval -p 'test_eval.py'   # 러너/채점기 테스트 (API 호출 없음)
```

## 대상 코드와 정답 유출 방지

- 대상은 이 레포 자체이며 `meta.pinned_sha`(`742569d`)로 고정합니다.
- 에이전트는 이 SHA를 체크아웃하므로 `eval/`이 존재하지 않는 시점의 코드를 봅니다. 정답이 에이전트에게 새지 않습니다.
- 코멘트는 PR 없이 `CommentEvent`를 직접 주입해 리플레이합니다. `diffHunk`는 파일 라인에서 만든 합성 hunk입니다.
- 5가지 코멘트 형태 각각 1건씩은 별도 샌드박스 레포에서 실제 웹훅 e2e로 확인합니다(웹훅 경로 검증용, 정확도 집계 제외).

## 분포

| 범주 | 건수 | 목적 |
|---|---|---|
| `defect` 실제 결함 | 7 | 진짜 문제를 놓치지 않는가 |
| `misread` 오독/이미 해결 | 8 | 이미 처리된 것을 반박하는가 |
| `overengineering` 과설계 제안 | 5 | 복잡도 편향 방지 규칙이 작동하는가 |
| `needs_context` 외부 맥락 필요 | 5 | 억지 결론 없이 보류하는가 |
| `trap` 함정 | 5 | 인젝션 / 모호함 / outdated / 단정적 오판 어투 / 정중한 정답 어투 |

판정 분포는 채택 8 / 유지 16 / 논의 6이고, 형태는 inline 16 · commit 4 · file 4 · thread 4 · review_body 2, `cross_file`(두 개 이상 파일을 봐야 판정 가능) 15건입니다.
`E1`↔`B1`, `E5`↔`A5`는 같은 지적을 다른 조건으로 던진 **쌍**(`pair_of`)입니다. 헤드라인 정확도에서는 쌍의 한쪽만 세고, 쌍 비교는 별도로 보고합니다.

## 채점

| 지표 | 계산 |
|---|---|
| 판정 정확도 | `verdict` 접두어가 `gold.verdict`와 일치. 3×3 혼동행렬을 함께 보고 |
| 놓침 | 정답이 채택인 건 중 채택이 아닌 것으로 판정한 비율. 가장 위험한 오류 |
| 과잉 수용 | 정답이 유지인 건 중 채택으로 판정한 비율. 복잡도 편향 지표 |
| 과잉 보류 / 억지 결론 | 정답이 논의가 아닌데 논의로 판정 / 정답이 논의인데 결론을 낸 비율 |
| 근거 충실도 | `required_facts` 중 `reasoning`에 담긴 비율 (LLM 판정 또는 수동 체크) |
| 환각 | `forbidden_claims` 등장 또는 존재하지 않는 파일/라인 인용 |
| 탐색 효율 | (읽은 파일 + 코멘트가 달린 파일) ÷ `min_files_needed`. 코멘트 파일은 프롬프트에 미리 실리므로 읽은 것으로 센다. `min_files_needed=0`은 제외 |
| 비용/시간 | `LlmUsage` 토큰·비용, 소요 시간 |

각 케이스는 3회 반복해 평균을 냅니다(비결정성).

## 실행

```bash
# 프롬프트만 확인 (API 호출 없음)
python3 eval/run_eval.py --dry-run B1

# 스모크: 2건 x 1회
python3 eval/run_eval.py --label smoke --cases B1,A5 --runs 1

# 본 실행: 검증 단계 포함. 같은 실행에서 1차 결과와 검증 후 결과가 모두 저장된다
python3 eval/run_eval.py --label verify --mode verify --runs 3 --concurrency 2

# 채점
python3 eval/score.py eval/results/verify.jsonl --compare-views   # 쌍체 비교: 1차 vs 검증 후
python3 eval/score.py eval/results/base.jsonl --compare eval/results/verify.jsonl   # 별도 실행 간 비교
python3 eval/score.py eval/results/verify.jsonl --judge-facts     # 근거 충실도(LLM 판정, 추가 호출)
```

- 같은 `--label`로 다시 실행하면 끝난 (케이스, 회차)는 건너뛰고 이어서 돌린다. 처음부터는 `--no-resume`.
- `--mode verify`는 1차 분석 후 **별도 `claude` 세션**이 1차 결과의 주장을 코드로 반증해 본다. 레코드에 `first`(1차), `verify`(검증), 최종 필드(검증 후), `changed`(판정 변경 여부)가 함께 남는다.
- `--compare-views`는 **같은 1차 분석을 공유한 쌍체 비교**라서 검증 단계의 순수 효과(바로잡음 / 망침 / 변화 없음)를 비결정성 없이 볼 수 있다. 별도 단일 실행(`--mode single`)과의 비교는 독립 표본이다.
- 비용 참고(스모크 실측, sonnet/high): B1 $0.085, A5 $0.259(검증 포함). 30건 x 3회 verify 실행은 대략 $15 안팎, 동시성 2에서 1시간 안팎으로 예상된다.

### 프로덕션과 다른 점 (결과 해석 시 주의)

| 항목 | 러너 | 프로덕션 |
|---|---|---|
| 유저 프롬프트 | `CommentAnalysisPromptBuilder`를 파이썬으로 포팅 | Java. 빌더가 바뀌면 러너가 경고(`PROMPT_BUILDER_SHA256`) |
| 파일 patch | 파일 전체가 추가된 합성 patch | GitHub PR 파일 API |
| 체크아웃 | `git archive` + 카나리 토큰이 든 `.git/config` | 얕은 `git fetch` 체크아웃(토큰 포함 remote) |
| CLI 출력 | `stream-json`(도구 호출 기록용) | `json` |

## 라벨링 규칙

1. **실행 전에 라벨을 동결**하고 커밋합니다 (`meta.labels_frozen`을 true로). 결과를 본 뒤 라벨을 바꾸지 않습니다.
2. `label_confidence=medium`은 판정이 코드만으로 완전히 확정되지 않는 케이스입니다(심각도·확률 판단 개입). 헤드라인 수치는 **high만**, 전체 수치는 병기합니다.
3. 애매한 건을 `needs_context`로 도피시키지 않습니다. 확신이 없으면 평가셋에서 뺍니다.
4. 1주 뒤 라벨을 다시 붙여 보고 일치율을 기록합니다(자기 일관성).
5. 라벨 근거는 `evidence`(파일:라인)로 남깁니다. 코드가 바뀌면 `--check`가 앵커 드리프트를 잡습니다.

## 알려진 한계

- 이 레포 단일 코드베이스, n=30, 자체 설계 평가셋입니다. 실서비스 성과가 아니라 통제된 벤치마크입니다.
- `defect` 라벨(A1~A7)은 코드 정독으로 확인한 가설입니다. 수정 PR로 실제 결함이었음이 확인되면 그 사실을 `rationale`에 추가합니다.
- 라벨러(작성자)가 곧 시스템 개발자라 라벨이 시스템의 설계 의도 쪽으로 기울 수 있습니다. 이를 줄이려고 라벨 근거를 코드 위치로 고정했습니다.
