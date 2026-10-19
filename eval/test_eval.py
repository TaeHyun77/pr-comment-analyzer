#!/usr/bin/env python3
"""러너/채점기 자체 검증. 실제 claude 호출 없이 스텁 CLI로 전체 흐름을 확인한다.

  python3 -m unittest discover -s eval -p 'test_eval.py' -v
"""
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

EVAL = Path(__file__).resolve().parent
sys.path.insert(0, str(EVAL))
import build_events as be  # noqa: E402
import run_eval as re_  # noqa: E402

STUB = r'''#!/usr/bin/env python3
import json, os, sys
prompt = sys.stdin.read()
mode = os.environ.get("STUB_MODE", "keep")
if mode == "fail":
    sys.exit(1)
verify = "[1차 분석 결과" in prompt
verdict, agree = "현 구현 유지 권장 — 스텁", True
if verify and os.environ.get("STUB_VERIFY_FLIP") == "1":
    verdict, agree = "제안 채택 권장 — 스텁", False
out = {"agree": agree, "verification_notes": "n", "verdict": verdict, "reasoning": "스텁 근거",
       "comment_summary": "s", "current_approach": "c", "suggested_approach": "x", "suggested_reply": "r"}
cfg = open(os.path.join(os.getcwd(), ".git", "config"), encoding="utf-8").read()
if "x-access-token" not in cfg:
    sys.exit(3)  # 체크아웃에 카나리 remote가 없으면 실패시켜 테스트가 잡게 한다
events = [
    {"type": "assistant", "message": {"content": [
        {"type": "tool_use", "name": "Read", "input": {"file_path": os.path.join(os.getcwd(), "build.gradle")}}]}},
    {"type": "result", "is_error": False, "result": json.dumps(out, ensure_ascii=False),
     "total_cost_usd": 0.01, "duration_ms": 1000, "num_turns": 2,
     "modelUsage": {"m": {"inputTokens": 10, "outputTokens": 5, "cacheReadInputTokens": 100, "cacheCreationInputTokens": 0}}},
]
for e in events:
    print(json.dumps(e, ensure_ascii=False))
'''


class Env:
    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.stub = Path(self.tmp.name) / "claude"
        self.stub.write_text(STUB, encoding="utf-8")
        self.stub.chmod(self.stub.stat().st_mode | stat.S_IEXEC)
        self.out = Path(self.tmp.name) / "results"

    def run(self, script, *args, env=None):
        e = dict(os.environ, CLAUDE_BIN=str(self.stub))
        e.update(env or {})
        return subprocess.run([sys.executable, str(EVAL / script), *args], capture_output=True, text=True,
                              encoding="utf-8", env=e)


class CasesTest(unittest.TestCase):
    def test_cases_validate(self):
        self.assertEqual(be.check(be.load()), 0)

    def test_thirty_cases_and_gold_distribution(self):
        cases = be.load()["cases"]
        self.assertEqual(len(cases), 30)
        verdicts = [c["gold"]["verdict"] for c in cases]
        self.assertEqual(verdicts.count("현 구현 유지 권장"), 16)


class PromptPortTest(unittest.TestCase):
    def test_slice_around_line_keeps_anchor_and_marks_range(self):
        content = "\n".join(f"line{i:03d}" for i in range(1, 101))
        out = re_.slice_around_line(content, 50, 80)
        self.assertIn("line050", out)
        self.assertIn("코멘트 라인 중심", out)
        self.assertNotIn("line001", out)

    def test_abbreviate_and_truncate(self):
        self.assertEqual(re_.abbreviate("abcdef", 4), "abc…")
        self.assertEqual(re_.abbreviate(None, 4), "(없음)")
        self.assertTrue(re_.truncate("a" * 10, 5).endswith("총 10자)"))

    def test_prompt_has_sections_and_no_gold_leak(self):
        data = be.load()
        meta = data["meta"]
        case = next(c for c in data["cases"] if c["id"] == "A2")
        idx = data["cases"].index(case) + 1
        cwd = re_.make_checkout(meta["pinned_sha"])
        try:
            ev = be.build_event(idx, case, meta)
            lines = be.git_show(meta["pinned_sha"], case["anchor"]["file"])
            prompt = re_.build_user_prompt(ev, case["thread"], cwd, lines)
            for section in ["[PR 정보]", "[코멘트 위치]", "[관련 코드/맥락]", "[이 파일의 전체 변경 diff]",
                            "[이전 스레드]", "[분석할 리뷰 코멘트]", "[코멘트가 달린 파일 전체 내용]"]:
                self.assertIn(section, prompt)
            self.assertNotIn("제안 채택 권장", prompt)  # 정답이 프롬프트에 섞이지 않는다
            self.assertNotIn("required_facts", prompt)
            self.assertNotIn("[eval", prompt)  # 케이스 ID가 새지 않는다
            git_config = (Path(cwd) / ".git" / "config").read_text()
            self.assertIn(re_.CANARY_TOKEN, git_config)
        finally:
            re_.cleanup(cwd)

    def test_checkout_has_no_eval_dir(self):
        cwd = re_.make_checkout(be.load()["meta"]["pinned_sha"])
        try:
            self.assertFalse((Path(cwd) / "eval").exists())
            self.assertTrue((Path(cwd) / "build.gradle").exists())
        finally:
            re_.cleanup(cwd)

    def test_normalize_verdict(self):
        self.assertEqual(re_.normalize_verdict("현 구현 유지 권장 — 이유"), "현 구현 유지 권장")
        self.assertIsNone(re_.normalize_verdict("모르겠음"))


class PipelineTest(unittest.TestCase):
    def test_single_run_score_resume(self):
        env = Env()
        p = env.run("run_eval.py", "--label", "t", "--runs", "1", "--concurrency", "4",
                    "--out-dir", str(env.out), "--max-attempts", "1")
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        recs = [json.loads(l) for l in (env.out / "t.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(recs), 30)
        self.assertTrue(all(r["error"] is None for r in recs))
        self.assertEqual(recs[0]["files_read"], ["build.gradle"])
        self.assertEqual(recs[0]["usage"]["total_tokens"], 115)

        again = env.run("run_eval.py", "--label", "t", "--runs", "1", "--out-dir", str(env.out))
        self.assertIn("대상 0건", again.stdout)  # 이미 끝난 건은 다시 돌리지 않는다

        s = env.run("score.py", str(env.out / "t.jsonl"), "--json", str(env.out / "t.json"))
        self.assertEqual(s.returncode, 0, s.stdout + s.stderr)
        summary = json.loads((env.out / "t.json").read_text(encoding="utf-8"))
        key = next(k for k in summary if k.endswith("[final]"))
        self.assertAlmostEqual(summary[key]["accuracy"], 16 / 30, places=6)  # 스텁은 늘 '유지'만 답한다
        self.assertIn("혼동행렬", s.stdout)
        self.assertEqual(summary[key]["leaks"], 0)

    def test_failing_cli_is_recorded_not_fatal(self):
        env = Env()
        p = env.run("run_eval.py", "--label", "f", "--runs", "1", "--cases", "B1,B2", "--out-dir", str(env.out),
                    "--max-attempts", "1", env={"STUB_MODE": "fail"})
        self.assertEqual(p.returncode, 1)
        recs = [json.loads(l) for l in (env.out / "f.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(recs), 2)
        self.assertTrue(all(r["error"] for r in recs))
        resumed = env.run("run_eval.py", "--label", "f", "--runs", "1", "--cases", "B1,B2", "--out-dir", str(env.out),
                          "--max-attempts", "1")
        self.assertIn("대상 2건", resumed.stdout)  # 실패한 건은 재실행 대상으로 남는다

    def test_verify_mode_paired_comparison(self):
        env = Env()
        p = env.run("run_eval.py", "--label", "v", "--mode", "verify", "--runs", "1", "--cases", "A1,B1,D1",
                    "--out-dir", str(env.out), "--max-attempts", "1", env={"STUB_VERIFY_FLIP": "1"})
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        recs = [json.loads(l) for l in (env.out / "v.jsonl").read_text(encoding="utf-8").splitlines()]
        r = next(x for x in recs if x["case_id"] == "A1")
        self.assertEqual(r["first"]["verdict"], "현 구현 유지 권장")
        self.assertEqual(r["verdict"], "제안 채택 권장")
        self.assertTrue(r["changed"])
        self.assertEqual(r["usage"]["total_tokens"], 230)  # 1차 + 검증 호출 합산

        s = env.run("score.py", str(env.out / "v.jsonl"), "--compare-views")
        self.assertEqual(s.returncode, 0, s.stdout + s.stderr)
        self.assertIn("바로잡음", s.stdout)  # A1(정답 채택)을 검증이 바로잡음
        self.assertIn("망침", s.stdout)  # B1(정답 유지)을 검증이 뒤집어 망침


if __name__ == "__main__":
    unittest.main()
