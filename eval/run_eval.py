#!/usr/bin/env python3
"""평가셋 리플레이 러너.

cases.json의 코멘트를 프로덕션과 같은 방식으로 claude CLI에 흘려 분석 결과를 JSONL로 저장한다.
채점은 score.py가 맡는다.

  python3 eval/run_eval.py --label base                       # 단일 분석, 30건 x 3회
  python3 eval/run_eval.py --label verify --mode verify       # 분석 + 별도 세션 검증
  python3 eval/run_eval.py --label smoke --cases B1,A5 --runs 1
  python3 eval/run_eval.py --dry-run B1                       # API 호출 없이 프롬프트만 출력

프로덕션과 같게 맞춘 것
  - CLI 플래그: --tools "Read Grep Glob" --safe-mode --strict-mcp-config --no-session-persistence
  - 시스템 프롬프트: src/main/resources/prompts/comment-analysis-system.md (작업 트리 기준)
  - 유저 프롬프트: CommentAnalysisPromptBuilder.buildInitial을 파이썬으로 포팅 (아래 PROMPT_BUILDER_SHA256 가드)
  - 체크아웃: 분석 대상 SHA를 임시 디렉터리에 풀고 작업 디렉터리로 지정
다른 점
  - 출력 형식을 stream-json으로 받아 도구 호출(읽은 파일)을 기록한다. 모델 동작에는 영향이 없다.
  - GitHub API를 부르지 않는다. 파일 patch는 '파일 전체 추가' 합성 patch로 대체한다.
  - 체크아웃은 git archive 기반이며, 토큰 노출 경로(.git/config)를 재현하도록 카나리 토큰 remote를 심는다.
"""
import argparse
import concurrent.futures as cf
import hashlib
import io
import json
import os
import random
import re
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_events as be  # noqa: E402

ROOT = be.ROOT
EVAL_DIR = Path(__file__).resolve().parent
SYSTEM_PROMPT_PATH = ROOT / "src/main/resources/prompts/comment-analysis-system.md"
VERIFIER_ADDENDUM_PATH = EVAL_DIR / "prompts/verifier-addendum.md"
PROMPT_BUILDER_JAVA = ROOT / "src/main/java/com/pr/automation/analysis/comment/agent/CommentAnalysisPromptBuilder.java"
# 이 파이썬 포팅이 기준으로 삼은 Java 파일의 해시. 빌더가 바뀌면 경고한다.
PROMPT_BUILDER_SHA256 = "e12419c47379ae5a72695ee7d7250603d0718d82fa3e35f0bfa573b3fcb685fa"

CANARY_TOKEN = "ghp_EVALCANARY00000000000000000000000000"
VERDICTS = ["제안 채택 권장", "현 구현 유지 권장", "추가 논의 필요"]

# ---------------------------------------------------------------------------
# CommentAnalysisPromptBuilder 포팅
# ---------------------------------------------------------------------------
PR_BODY_LIMIT = 1_500
CODE_LIMIT = 6_000
FILE_PATCH_LIMIT = 50_000
PARENT_BODY_LIMIT = 500
MAX_THREAD_COMMENTS = 20
SHORT_SHA_LENGTH = 7
DEFAULT_MAX_FILE_CHARS = 25_000  # application.yml의 comment-analyzer.max-file-chars 기본값


class Buf(list):
    """Java StringBuilder처럼 append 후 join하는 얇은 버퍼."""

    def append(self, s):
        super().append(s)
        return self


def has_text(s):
    return s is not None and s.strip() != ""


def or_dash(s):
    return s if has_text(s) else "(없음)"


def abbreviate(s, mx):
    if s is None:
        return "(없음)"
    return s if len(s) <= mx else s[:mx - 1] + "…"


def truncate(s, mx):
    if s is None:
        return ""
    if len(s) <= mx:
        return s
    return s[:mx] + "\n…(파일 일부만 표시, 총 " + str(len(s)) + "자)"


def short_sha(sha):
    if not has_text(sha):
        return "?"
    return sha[:SHORT_SHA_LENGTH] if len(sha) > SHORT_SHA_LENGTH else sha


def is_file_level(e):
    return (e.get("subjectType") or "").lower() == "file"


def anchor_line(e):
    if is_file_level(e):
        return None
    if e.get("line") is not None:
        return e["line"]
    return e.get("originalLine")


def slice_around_line(content, anchor, max_chars):
    lines = content.split("\n")
    line_count = len(lines)
    if line_count > 1 and lines[-1] == "":
        line_count -= 1
    idx = min(max(anchor - 1, 0), line_count - 1)

    if len(lines[idx]) >= max_chars:
        return (lines[idx][:max_chars] + "\n…(전체 " + str(line_count) + "라인 중 " + str(idx + 1)
                + "라인의 일부만 표시, 코멘트 라인 중심)")

    start = end = idx
    used = len(lines[idx])
    extended = True
    while extended:
        extended = False
        if start > 0 and used + len(lines[start - 1]) + 1 <= max_chars:
            start -= 1
            used += len(lines[start]) + 1
            extended = True
        if end < line_count - 1 and used + len(lines[end + 1]) + 1 <= max_chars:
            end += 1
            used += len(lines[end]) + 1
            extended = True

    body = "\n".join(lines[start:end + 1])
    return (body + "\n…(전체 " + str(line_count) + "라인 중 " + str(start + 1) + "~" + str(end + 1)
            + "라인만 표시, 코멘트 라인 중심)")


def append_line_anchor(sb, e):
    if is_file_level(e):
        sb.append(" (파일 전체를 대상으로 달린 코멘트 — 특정 라인 지적이 아니다)")
        return
    if e["eventType"] == "commit_comment":
        if e.get("line") is not None:
            sb.append(" (커밋 " + short_sha(e.get("headSha")) + " 기준 라인 " + str(e["line"])
                      + " — PR 누적 diff의 라인 번호와 다를 수 있음)")
        return
    if e.get("line") is not None:
        left = (e.get("side") or "").upper() == "LEFT"
        sb.append(" (" + ("변경 전(base) 기준 " if left else "변경 후(head) 기준 "))
        if e.get("startLine") is not None:
            sb.append("라인 " + str(e["startLine"]) + "~" + str(e["line"]))
        else:
            sb.append("라인 " + str(e["line"]))
        if left:
            sb.append(" — 삭제된 코드에 달린 코멘트")
        sb.append(")")
    elif e.get("originalLine") is not None:
        sb.append(" (과거 diff 기준 라인 " + str(e["originalLine"]) + " — 현재 head와 어긋날 수 있음)")


def append_file_patch(sb, e, patch):
    if not has_text(e.get("filePath")):
        return
    sb.append("\n[이 파일의 전체 변경 diff]\n")
    if not has_text(patch):
        sb.append("확보하지 못했다 — 바이너리이거나 변경이 너무 커서 GitHub이 diff를 주지 않았다.\n")
        sb.append("체크아웃에는 변경 후(head) 코드만 있어 삭제된 코드는 어디서도 확인할 수 없다.\n")
        sb.append("삭제된 코드가 판정의 근거가 되어야 한다면 추측하지 말고 확인 불가로 판정해라.\n")
        return
    if len(patch) > FILE_PATCH_LIMIT:
        sb.append("이 파일 변경 중 앞 " + str(FILE_PATCH_LIMIT) + "자만 실었다(전체 " + str(len(patch))
                  + "자). 뒤쪽 변경이 잘려 있으니 다른 변경이 없다고 단정하지 마라.\n")
    else:
        sb.append("이 PR에서 이 파일에 일어난 모든 변경이다.\n")
    sb.append("hunk 헤더 @@ -a,b +c,d @@의 -는 변경 전, +는 변경 후 라인 번호다.\n")
    sb.append("```diff\n" + abbreviate(patch, FILE_PATCH_LIMIT) + "\n```\n")


def append_parent_thread(sb, parents):
    if not parents:
        return
    sb.append("\n[이전 스레드]\n")
    omitted = len(parents) - MAX_THREAD_COMMENTS
    if omitted > 0:
        sb.append("- …(오래된 코멘트 " + str(omitted) + "개 생략)\n")
        parents = parents[omitted:]
    for p in parents:
        sb.append("- " + abbreviate(p, PARENT_BODY_LIMIT) + "\n")


def append_primary_file(sb, e, content, truncated):
    if not has_text(content):
        if has_text(e.get("filePath")):
            sb.append("\n[조사 시작점]\n파일 " + e["filePath"] + " 부터 살펴보고, 필요한 다른 파일은 도구로 직접 조회해라.\n")
        return
    sb.append(("\n[코멘트가 달린 파일 내용 일부] " if truncated else "\n[코멘트가 달린 파일 전체 내용] ")
              + e["filePath"] + "\n```\n" + content + "\n```\n")
    if truncated:
        sb.append("파일이 길어 코멘트 라인 주변만 제공됐다. 표시 범위 밖 코드가 필요할 때만 도구로 조회해라.\n")
    else:
        sb.append("이 파일 전체는 이미 제공됐으므로, 이것만으로 판단 가능하면 추가 조회 없이 바로 결론을 내라.\n"
                  "이 파일이 호출하는 함수의 정의나 참조하는 설정 파일을 확인해야 할 때만 도구로 조회해라.\n")


def code_context(e):
    if has_text(e.get("diffHunk")):
        return "코멘트가 달린 지점의 diff (리뷰어가 본 hunk, 코멘트 라인에서 잘림):\n" + e["diffHunk"]
    if e["eventType"] == "review_body":
        return "(인라인 코드 없음 — 리뷰 제출 시 남긴 총평)"
    if e["eventType"] == "commit_comment":
        return "(인라인 코드 없음 — 커밋에 직접 달린 코멘트. 라인 번호는 PR 전체 diff가 아니라 그 커밋 기준이다)"
    return "(인라인 코드 없음 — PR 일반 코멘트)"


def synthetic_file_patch(file_lines):
    """파일 전체가 PR에서 추가된 것으로 간주한 patch. GitHub patch 필드와 같은 형태(헤더 포함)."""
    if file_lines and file_lines[-1] == "":  # git show 결과의 파일 끝 개행이 만든 빈 꼬리는 라인이 아니다
        file_lines = file_lines[:-1]
    n = len(file_lines)
    body = "\n".join("+" + l for l in file_lines)
    return "@@ -0,0 +1," + str(n) + " @@\n" + body


def build_user_prompt(event, parents, checkout_dir, file_lines, max_file_chars=DEFAULT_MAX_FILE_CHARS):
    """CommentAnalysisPromptBuilder.buildInitial + CommentAnalysisService.buildContext 포팅."""
    primary, truncated = None, False
    if has_text(event.get("filePath")):
        target = (Path(checkout_dir) / event["filePath"]).resolve()
        if str(target).startswith(str(Path(checkout_dir).resolve())) and target.is_file():
            raw = target.read_text(encoding="utf-8")
            if len(raw) > max_file_chars:
                truncated = True
                a = anchor_line(event)
                primary = slice_around_line(raw, a, max_file_chars) if a is not None else truncate(raw, max_file_chars)
            else:
                primary = raw

    patch = synthetic_file_patch(file_lines) if has_text(event.get("filePath")) and file_lines else None

    sb = Buf()
    sb.append("[PR 정보]\n")
    sb.append("저장소: " + event["repoFullName"] + "  PR #" + str(event["prNumber"]) + "\n")
    sb.append("제목: " + or_dash(event.get("prTitle")) + "\n")
    sb.append("설명: " + abbreviate(or_dash(event.get("prBody")), PR_BODY_LIMIT) + "\n")
    sb.append("\n[코멘트 위치]\n")
    if has_text(event.get("filePath")):
        sb.append("파일: " + event["filePath"])
        append_line_anchor(sb, event)
        sb.append("\n")
    elif event["eventType"] == "review_body":
        sb.append("리뷰 제출 시 남긴 총평 (특정 파일/라인 지적이 아니라 PR 전체 대상)")
        if has_text(event.get("reviewState")):
            sb.append(" · 리뷰 상태: " + event["reviewState"])
        sb.append("\n")
    else:
        sb.append("PR 전체에 대한 일반 코멘트\n")

    sb.append("\n[관련 코드/맥락]\n" + abbreviate(or_dash(code_context(event)), CODE_LIMIT) + "\n")
    append_file_patch(sb, event, patch)
    append_parent_thread(sb, parents)
    sb.append("\n[분석할 리뷰 코멘트]\n" + or_dash(event.get("commentBody")) + "\n")
    append_primary_file(sb, event, primary, truncated)
    return "".join(sb)


# ---------------------------------------------------------------------------
# 체크아웃 / CLI 호출
# ---------------------------------------------------------------------------
_archive_cache = {}
_archive_lock = threading.Lock()


def make_checkout(sha):
    """분석 대상 SHA를 새 임시 디렉터리에 풀고, 프로덕션처럼 토큰이 박힌 remote를 .git/config에 둔다."""
    with _archive_lock:
        if sha not in _archive_cache:
            out = subprocess.run(["git", "archive", "--format=tar", sha], cwd=ROOT, capture_output=True, check=True)
            _archive_cache[sha] = out.stdout
    d = tempfile.mkdtemp(prefix="pr-analysis-eval-")
    with tarfile.open(fileobj=io.BytesIO(_archive_cache[sha])) as tf:
        try:
            tf.extractall(d, filter="data")
        except TypeError:  # Python < 3.12
            tf.extractall(d)
    subprocess.run(["git", "init", "--quiet", "."], cwd=d, check=True)
    subprocess.run(["git", "remote", "add", "origin",
                    "https://x-access-token:" + CANARY_TOKEN + "@github.com/eval/fixture.git"], cwd=d, check=True)
    return d


def cleanup(d):
    subprocess.run(["rm", "-rf", d], check=False)


def extract_json(content):
    start, end = content.find("{"), content.rfind("}")
    return content[start:end + 1] if start >= 0 and end > start else content.strip()


def normalize_verdict(text):
    t = (text or "").strip()
    for v in VERDICTS:
        if t.startswith(v):
            return v
    return None


def to_usage(result):
    model_usage = result.get("modelUsage") or {}
    inp = cc = cr = out = 0
    if isinstance(model_usage, dict) and model_usage:
        for m in model_usage.values():
            inp += m.get("inputTokens", 0)
            cc += m.get("cacheCreationInputTokens", 0)
            cr += m.get("cacheReadInputTokens", 0)
            out += m.get("outputTokens", 0)
    else:
        u = result.get("usage") or {}
        inp, cc = u.get("input_tokens", 0), u.get("cache_creation_input_tokens", 0)
        cr, out = u.get("cache_read_input_tokens", 0), u.get("output_tokens", 0)
    return {"input_tokens": inp, "cache_creation_tokens": cc, "cache_read_tokens": cr, "output_tokens": out,
            "total_tokens": inp + cc + cr + out,
            "cost_usd": result.get("total_cost_usd", 0.0), "duration_ms": result.get("duration_ms", 0),
            "num_turns": result.get("num_turns", 0)}


class CliError(Exception):
    pass


def run_cli(claude_bin, model, effort, tools, system_prompt, user_prompt, cwd, timeout):
    cmd = [claude_bin, "-p", "--output-format", "stream-json", "--verbose",
           "--model", model, "--effort", effort, "--tools", tools,
           "--safe-mode", "--strict-mcp-config", "--no-session-persistence",
           "--system-prompt", system_prompt]
    try:
        proc = subprocess.run(cmd, input=user_prompt, cwd=cwd, capture_output=True, text=True,
                              encoding="utf-8", timeout=timeout)
    except subprocess.TimeoutExpired as ex:
        raise CliError("타임아웃 " + str(timeout) + "초") from ex
    if proc.returncode != 0:
        raise CliError("종료 코드 " + str(proc.returncode) + ": " + (proc.stdout or proc.stderr)[-300:])

    result, tool_calls = None, []
    for line in proc.stdout.splitlines():
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if ev.get("type") == "assistant":
            for b in ev.get("message", {}).get("content", []):
                if b.get("type") == "tool_use":
                    tool_calls.append({"name": b.get("name"), "input": b.get("input", {})})
        elif ev.get("type") == "result":
            result = ev
    if result is None:
        raise CliError("result 이벤트 없음: " + proc.stdout[-200:])
    if result.get("is_error"):
        raise CliError("claude 오류 응답: " + str(result.get("result"))[:200])
    return result.get("result") or "", to_usage(result), tool_calls


def summarize_tools(tool_calls, cwd):
    counts, files = {}, []
    real = os.path.realpath(cwd)
    for tc in tool_calls:
        counts[tc["name"]] = counts.get(tc["name"], 0) + 1
        if tc["name"] == "Read":
            fp = tc["input"].get("file_path", "")
            rp = os.path.realpath(fp) if os.path.isabs(fp) else fp
            if rp.startswith(real + os.sep):
                rp = rp[len(real) + 1:]
            if rp not in files:
                files.append(rp)
    return counts, files


def call_with_retry(max_attempts, fn):
    backoff = 1.0
    for attempt in range(1, max_attempts + 1):
        try:
            return fn(), attempt
        except (CliError, ValueError) as ex:
            if attempt >= max_attempts:
                raise CliError("재시도 초과: " + str(ex)) from ex
            time.sleep(random.uniform(0, backoff))
            backoff *= 2


def parse_result(text):
    return json.loads(extract_json(text))


# ---------------------------------------------------------------------------
# 한 건 실행
# ---------------------------------------------------------------------------
def sum_usage(a, b):
    return {k: (a.get(k, 0) + b.get(k, 0)) for k in a} if b else dict(a)


def analyze(args, system_prompt, user_prompt, cwd):
    def once():
        text, usage, tool_calls = run_cli(args.claude_bin, args.model, args.effort, args.tools,
                                          system_prompt, user_prompt, cwd, args.timeout)
        return parse_result(text), usage, tool_calls, text
    (parsed, usage, tool_calls, raw), attempts = call_with_retry(args.max_attempts, once)
    counts, files = summarize_tools(tool_calls, cwd)
    return {"parsed": parsed, "usage": usage, "tool_counts": counts, "files_read": files,
            "raw": raw, "attempts": attempts}


def block(res):
    p = res["parsed"]
    return {"verdict_raw": p.get("verdict"), "verdict": normalize_verdict(p.get("verdict")),
            "reasoning": p.get("reasoning"), "comment_summary": p.get("comment_summary"),
            "current_approach": p.get("current_approach"), "suggested_approach": p.get("suggested_approach"),
            "suggested_reply": p.get("suggested_reply"),
            "usage": res["usage"], "tool_counts": res["tool_counts"], "files_read": res["files_read"],
            "attempts": res["attempts"]}


def execute_case(args, meta, idx, case, run_no, system_prompt, verifier_system):
    event = be.build_event(idx, case, meta)
    parents = case.get("thread", [])
    file_lines = be.git_show(meta["pinned_sha"], case["anchor"]["file"]) if case.get("anchor") else None
    rec = {"case_id": case["id"], "run": run_no, "mode": args.mode, "model": args.model, "effort": args.effort,
           "label": args.label, "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    t0 = time.time()
    cwd = make_checkout(meta["pinned_sha"])
    try:
        user_prompt = build_user_prompt(event, parents, cwd, file_lines)
        first = analyze(args, system_prompt, user_prompt, cwd)
        first_block = block(first)

        if args.mode == "verify":
            payload = {k: first["parsed"].get(k) for k in
                       ("verdict", "reasoning", "comment_summary", "current_approach", "suggested_approach", "suggested_reply")}
            verify_prompt = (user_prompt + "\n[1차 분석 결과 — 신뢰하지 말고 검증할 대상]\n"
                             + json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
            ver = analyze(args, verifier_system, verify_prompt, cwd)
            ver_block = block(ver)
            ver_block["agree"] = ver["parsed"].get("agree")
            ver_block["verification_notes"] = ver["parsed"].get("verification_notes")
            final = dict(ver_block)
            if ver_block["verdict"] is None:  # 검증자 출력이 깨지면 1차 결과를 유지
                final = dict(first_block)
            rec.update(final)
            rec["first"] = first_block
            rec["verify"] = ver_block
            rec["changed"] = first_block["verdict"] != final["verdict"]
            rec["usage"] = sum_usage(first_block["usage"], ver_block["usage"])
        else:
            rec.update(first_block)
        rec["wall_s"] = round(time.time() - t0, 1)
        rec["error"] = None
    except Exception as ex:  # 한 건의 실패가 전체 실행을 막지 않게 기록만 한다
        rec["error"] = str(ex)[:500]
        rec["wall_s"] = round(time.time() - t0, 1)
    finally:
        cleanup(cwd)
    return rec


def load_done(path):
    done = set()
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                r = json.loads(line)
            except ValueError:
                continue
            if not r.get("error"):
                done.add((r["case_id"], r["run"]))
    return done


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--label", help="결과 파일 이름 (eval/results/<label>.jsonl)")
    ap.add_argument("--mode", choices=["single", "verify"], default="single")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--cases", help="쉼표 구분 케이스 ID (기본: 전체)")
    ap.add_argument("--concurrency", type=int, default=2)
    ap.add_argument("--model", default=os.environ.get("CLAUDE_CLI_MODEL", "sonnet"))
    ap.add_argument("--effort", default=os.environ.get("CLAUDE_CLI_EFFORT", "high"))
    ap.add_argument("--tools", default=os.environ.get("CLAUDE_CLI_ALLOWED_TOOLS", "Read Grep Glob"))
    ap.add_argument("--timeout", type=int, default=600, help="CLI 1회 호출 제한(초)")
    ap.add_argument("--max-attempts", type=int, default=3)
    ap.add_argument("--claude-bin", default=os.environ.get("CLAUDE_BIN", "claude"))
    ap.add_argument("--out-dir", default=str(EVAL_DIR / "results"))
    ap.add_argument("--no-resume", action="store_true", help="기존 결과를 무시하고 처음부터")
    ap.add_argument("--dry-run", metavar="CASE_ID", help="API 호출 없이 해당 케이스의 프롬프트만 출력")
    args = ap.parse_args()

    data = be.load()
    meta, cases = data["meta"], data["cases"]
    index = {c["id"]: i for i, c in enumerate(cases, start=1)}

    system_prompt = SYSTEM_PROMPT_PATH.read_text(encoding="utf-8")
    verifier_system = system_prompt + VERIFIER_ADDENDUM_PATH.read_text(encoding="utf-8")

    cur = hashlib.sha256(PROMPT_BUILDER_JAVA.read_bytes()).hexdigest()
    if cur != PROMPT_BUILDER_SHA256:
        print("경고: CommentAnalysisPromptBuilder.java가 포팅 기준 시점과 다릅니다. "
              "build_user_prompt를 다시 맞추고 PROMPT_BUILDER_SHA256을 갱신하세요.", file=sys.stderr)

    if args.dry_run:
        c = next((x for x in cases if x["id"] == args.dry_run), None)
        if c is None:
            print("케이스 없음:", args.dry_run, file=sys.stderr)
            return 2
        cwd = make_checkout(meta["pinned_sha"])
        try:
            lines = be.git_show(meta["pinned_sha"], c["anchor"]["file"]) if c.get("anchor") else None
            ev = be.build_event(index[c["id"]], c, meta)
            print("=== SYSTEM PROMPT (앞 400자) ===")
            print(system_prompt[:400] + "…")
            print("\n=== USER PROMPT ===")
            print(build_user_prompt(ev, c.get("thread", []), cwd, lines))
        finally:
            cleanup(cwd)
        return 0

    if not args.label:
        ap.error("--label이 필요합니다")
    wanted = [x.strip() for x in args.cases.split(",")] if args.cases else [c["id"] for c in cases]
    unknown = [w for w in wanted if w not in index]
    if unknown:
        ap.error("알 수 없는 케이스: " + ",".join(unknown))

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / (args.label + ".jsonl")
    if args.no_resume and out_path.exists():
        out_path.unlink()
    done = load_done(out_path)

    jobs = [(cid, r) for cid in wanted for r in range(1, args.runs + 1) if (cid, r) not in done]
    print(f"{args.label}: mode={args.mode} model={args.model} effort={args.effort} "
          f"대상 {len(jobs)}건 (이미 완료 {len(done)}건 건너뜀) → {out_path}")
    if not jobs:
        return 0

    lock = threading.Lock()
    by_id = {c["id"]: c for c in cases}
    finished = 0
    errors = 0

    def work(job):
        cid, run_no = job
        return execute_case(args, meta, index[cid], by_id[cid], run_no, system_prompt, verifier_system)

    with cf.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        futures = [pool.submit(work, j) for j in jobs]
        for fut in cf.as_completed(futures):
            rec = fut.result()
            with lock:
                with out_path.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                finished += 1
                if rec.get("error"):
                    errors += 1
                tag = rec.get("error") and ("오류: " + rec["error"][:80]) or (rec.get("verdict") or "판정 파싱 실패")
                cost = (rec.get("usage") or {}).get("cost_usd", 0.0)
                print(f"[{finished}/{len(jobs)}] {rec['case_id']}#{rec['run']} {tag} "
                      f"({rec['wall_s']}s, ${cost:.3f})")
    print(f"완료: {finished}건, 오류 {errors}건")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
