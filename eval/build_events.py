#!/usr/bin/env python3
"""평가셋(cases.json) 검증 및 CommentEvent 리플레이 입력 생성.

  python3 eval/build_events.py --check          # 스키마/앵커/분포 검증 (라벨 동결 전 필수)
  python3 eval/build_events.py --emit eval/out  # 케이스별 리플레이 입력 JSON 생성

정답(gold)은 emit 결과에 포함하지 않는다. 에이전트는 pinned_sha를 체크아웃하므로
eval/ 디렉터리가 존재하지 않는 커밋을 보게 되어 정답이 새지 않는다.
"""
import argparse
import collections
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CASES = Path(__file__).resolve().parent / "cases.json"

CATEGORIES = {"defect", "misread", "overengineering", "needs_context", "trap"}
FORMS = {"inline", "file", "thread", "review_body", "commit"}
CONFIDENCE = {"high", "medium"}
DIFFICULTY = {"easy", "medium", "hard"}
EVENT_TYPE = {
    "inline": "review_comment", "file": "review_comment", "thread": "review_comment",
    "review_body": "review_body", "commit": "commit_comment",
}
FIXTURE_PR_NUMBER = 1
COMMENT_ID_BASE = 900_000_000


def load():
    with CASES.open(encoding="utf-8") as f:
        return json.load(f)


def git_show(sha, path):
    out = subprocess.run(["git", "show", f"{sha}:{path}"], cwd=ROOT, capture_output=True)
    if out.returncode != 0:
        raise RuntimeError(f"git show {sha[:7]}:{path} 실패: {out.stderr.decode().strip()}")
    return out.stdout.decode("utf-8").split("\n")


def check(data):
    meta, cases = data["meta"], data["cases"]
    verdicts = set(meta["verdict_values"])
    sha = meta["pinned_sha"]
    errors = []
    seen = set()

    for c in cases:
        cid = c.get("id", "?")
        if cid in seen:
            errors.append(f"{cid}: 중복 ID")
        seen.add(cid)
        if c["category"] not in CATEGORIES:
            errors.append(f"{cid}: category 값 오류")
        if c["form"] not in FORMS:
            errors.append(f"{cid}: form 값 오류")
        if c["label_confidence"] not in CONFIDENCE:
            errors.append(f"{cid}: label_confidence 값 오류")
        if c["difficulty"] not in DIFFICULTY:
            errors.append(f"{cid}: difficulty 값 오류")
        g = c["gold"]
        if g["verdict"] not in verdicts:
            errors.append(f"{cid}: gold.verdict 값 오류")
        if c["form"] != "review_body" and g["required_facts"] == []:
            errors.append(f"{cid}: required_facts 비어 있음")
        if c["form"] == "thread" and not c.get("thread"):
            errors.append(f"{cid}: thread 폼인데 thread가 없음")
        if c.get("pair_of") and c["pair_of"] not in {x["id"] for x in cases}:
            errors.append(f"{cid}: pair_of 대상 없음")
        if g["min_files_needed"] > len(g["required_files"]) and g["min_files_needed"] > 0:
            errors.append(f"{cid}: min_files_needed > len(required_files)")
        for rf in g["required_files"]:
            try:
                git_show(sha, rf)
            except RuntimeError as e:
                errors.append(f"{cid}: required_file 없음 ({rf}): {e}")

        a = c.get("anchor")
        if c["form"] == "review_body":
            if a is not None:
                errors.append(f"{cid}: review_body는 anchor가 없어야 함")
            continue
        if a is None:
            errors.append(f"{cid}: anchor 없음")
            continue
        try:
            lines = git_show(sha, a["file"])
        except RuntimeError as e:
            errors.append(f"{cid}: {e}")
            continue
        ln = a["line"] if a["line"] is not None else a.get("original_line")
        if ln is None or not (1 <= ln <= len(lines)):
            errors.append(f"{cid}: 앵커 라인 범위 오류 ({ln})")
            continue
        if a["text"] not in lines[ln - 1]:
            errors.append(f"{cid}: {a['file']}:{ln}에 '{a['text']}' 없음 → 실제: {lines[ln - 1].strip()[:80]!r}")

    print(f"케이스 {len(cases)}건")
    def dist(title, key):
        cnt = collections.Counter(key(c) for c in cases)
        print(f"  {title}: " + ", ".join(f"{k}={v}" for k, v in sorted(cnt.items())))
    dist("판정(gold)", lambda c: c["gold"]["verdict"])
    dist("범주", lambda c: c["category"])
    dist("폼", lambda c: c["form"])
    dist("난이도", lambda c: c["difficulty"])
    dist("라벨 신뢰도", lambda c: c["label_confidence"])
    dist("cross_file", lambda c: c.get("cross_file", False))

    if errors:
        print(f"\n오류 {len(errors)}건:")
        for e in errors:
            print("  -", e)
        return 1
    print("\n검증 통과")
    return 0


def diff_hunk(lines, start, end):
    """코멘트 라인까지의 합성 hunk. 평가용 파일은 PR에서 추가된 것으로 간주한다."""
    first = max(1, min(start, end) - 5)
    body = "\n".join("+" + l for l in lines[first - 1:end])
    return f"@@ -0,0 +{first},{end - first + 1} @@\n{body}"


def build_event(idx, c, meta):
    cid = COMMENT_ID_BASE + idx
    event = {
        "eventType": EVENT_TYPE[c["form"]],
        "repoFullName": meta["target_repo"],
        "prNumber": FIXTURE_PR_NUMBER,
        "prTitle": "평가용 고정 PR",
        "prBody": "평가용 가상 PR. 코멘트 대상 코드는 pinned_sha 시점의 파일이다.",
        "headSha": meta["pinned_sha"],
        "commentId": cid,
        "commentBody": c["comment"],
        "commentAuthor": "eval-reviewer",
        "commentHtmlUrl": f"https://example.invalid/eval/{c['id']}",
    }
    a = c.get("anchor")
    if c["form"] == "review_body":
        event["reviewState"] = c.get("review_state", "commented")
        return event

    lines = git_show(meta["pinned_sha"], a["file"])
    event["filePath"] = a["file"]
    if c["form"] == "file":
        event["subjectType"] = "file"
        event["line"] = 1  # GitHub이 파일 단위 코멘트에도 line=1을 채워 보내는 동작을 재현
        event["diffHunk"] = diff_hunk(lines, 1, min(len(lines), 6))
    elif c["form"] == "commit":
        event["subjectType"] = "line"
        event["line"] = a["line"]
    else:
        event["subjectType"] = "line"
        event["side"] = "RIGHT"
        if a["line"] is not None:
            event["line"] = a["line"]
            event["diffHunk"] = diff_hunk(lines, a["line"], a["line"])
        else:  # outdated: 현재 head에는 라인이 없고 작성 시점 라인만 남는다
            event["originalLine"] = a["original_line"]
            event["diffHunk"] = diff_hunk(lines, a["original_line"], a["original_line"])
        if c["form"] == "thread":
            event["inReplyToId"] = cid - 1
    return event


def emit(data, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for idx, c in enumerate(data["cases"], start=1):
        payload = {
            "id": c["id"],
            "event": build_event(idx, c, data["meta"]),
            "parentComments": c.get("thread", []),
        }
        (out / f"{c['id']}.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(data['cases'])}건 → {out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--emit", metavar="DIR")
    args = ap.parse_args()
    data = load()
    if not args.check and not args.emit:
        ap.print_help()
        return 2
    rc = check(data) if args.check else 0
    if args.emit and rc == 0:
        emit(data, args.emit)
    return rc


if __name__ == "__main__":
    sys.exit(main())
