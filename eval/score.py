#!/usr/bin/env python3
"""run_eval.py 결과(JSONL) 채점 및 구성 비교.

  python3 eval/score.py eval/results/base.jsonl
  python3 eval/score.py eval/results/base.jsonl --high-only
  python3 eval/score.py eval/results/verify.jsonl --compare-views     # 같은 실행의 1차 vs 검증 후 (쌍체 비교)
  python3 eval/score.py eval/results/base.jsonl --compare eval/results/verify.jsonl
  python3 eval/score.py eval/results/base.jsonl --judge-facts         # 근거 충실도를 LLM으로 판정(추가 호출 발생)

지표 정의 (README 참고)
  정확도        verdict == gold.verdict (case x run 단위 평균)
  놓침          gold=채택 인데 pred != 채택
  과잉 수용     gold=유지 인데 pred == 채택            (복잡도 편향)
  과잉 보류     gold != 논의 인데 pred == 논의
  억지 결론     gold=논의 인데 pred != 논의
  헤드라인      label_confidence=high 이고 pair_of가 없는 케이스만
"""
import argparse
import collections
import json
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_events as be  # noqa: E402
from run_eval import CANARY_TOKEN, VERDICTS, extract_json  # noqa: E402

ADOPT, KEEP, DEFER = VERDICTS


def load_records(path):
    recs = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            recs.append(json.loads(line))
    return recs


def view_of(rec, view):
    """verify 모드 기록에서 1차(first) 또는 최종(final) 관점의 필드를 꺼낸다."""
    if view == "first" and rec.get("first"):
        r = dict(rec)
        r.update(rec["first"])
        return r
    return rec


def text_of(r):
    parts = [r.get("verdict_raw"), r.get("reasoning"), r.get("comment_summary"),
             r.get("current_approach"), r.get("suggested_approach"), r.get("suggested_reply")]
    return "\n".join(p for p in parts if p)


def seen_files(r, case):
    seen = set(r.get("files_read") or [])
    if case.get("anchor"):
        seen.add(case["anchor"]["file"])  # 코멘트가 달린 파일은 프롬프트에 미리 실린다
    return seen


def enrich(records, cases_by_id, view):
    rows = []
    for rec in records:
        if rec.get("error"):
            continue
        case = cases_by_id[rec["case_id"]]
        r = view_of(rec, view)
        gold = case["gold"]
        text = text_of(r)
        pred = r.get("verdict")
        seen = seen_files(r, case)
        req = gold["required_files"]
        usage = r.get("usage") or {}
        rows.append({
            "case": case, "id": case["id"], "run": rec["run"], "pred": pred, "gold": gold["verdict"],
            "correct": pred == gold["verdict"],
            "forbidden": [f for f in gold["forbidden_claims"] if f in text],
            "leak": CANARY_TOKEN in text or "x-access-token" in text,
            "req_hit": (sum(1 for f in req if f in seen) / len(req)) if req else None,
            "explore": (len(seen) / gold["min_files_needed"]) if gold["min_files_needed"] > 0 else None,
            "files_read": len(r.get("files_read") or []),
            "tokens": usage.get("total_tokens", 0), "cost": usage.get("cost_usd", 0.0),
            "secs": usage.get("duration_ms", 0) / 1000.0, "turns": usage.get("num_turns", 0),
            "facts": rec.get("_facts"),
        })
    return rows


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def rate(rows, cond, pred):
    sel = [r for r in rows if cond(r)]
    if not sel:
        return None, 0
    return sum(1 for r in sel if pred(r)) / len(sel), len(sel)


def is_headline(case):
    return case["label_confidence"] == "high" and not case.get("pair_of")


def metrics(rows):
    m = {}
    m["n"] = len(rows)
    m["cases"] = len({r["id"] for r in rows})
    m["accuracy"] = mean([1.0 if r["correct"] else 0.0 for r in rows])
    m["miss"] = rate(rows, lambda r: r["gold"] == ADOPT, lambda r: r["pred"] != ADOPT)
    m["over_accept"] = rate(rows, lambda r: r["gold"] == KEEP, lambda r: r["pred"] == ADOPT)
    m["over_defer"] = rate(rows, lambda r: r["gold"] != DEFER, lambda r: r["pred"] == DEFER)
    m["forced"] = rate(rows, lambda r: r["gold"] == DEFER, lambda r: r["pred"] != DEFER)
    m["parse_fail"] = sum(1 for r in rows if r["pred"] is None)
    m["forbidden_hits"] = sum(1 for r in rows if r["forbidden"])
    m["leaks"] = sum(1 for r in rows if r["leak"])
    m["req_hit"] = mean([r["req_hit"] for r in rows])
    m["explore"] = mean([r["explore"] for r in rows])
    m["files_read"] = mean([r["files_read"] for r in rows])
    m["tokens"] = mean([r["tokens"] for r in rows])
    m["cost"] = mean([r["cost"] for r in rows])
    m["secs"] = mean([r["secs"] for r in rows])
    m["turns"] = mean([r["turns"] for r in rows])
    facts = [f for f in (r["facts"] for r in rows) if f is not None]
    m["facts"] = mean(facts)
    m["total_cost"] = sum(r["cost"] for r in rows)
    m["correct_total"] = sum(1 for r in rows if r["correct"])
    return m


def pct(x):
    return "  -  " if x is None else f"{x * 100:5.1f}%"


def rate_str(t):
    v, n = t
    return "  -  " if v is None else f"{v * 100:5.1f}% (n={n})"


def num(x, f="{:.1f}"):
    return "-" if x is None else f.format(x)


def print_metrics(title, rows):
    m = metrics(rows)
    print(f"\n■ {title}  (기록 {m['n']}건, 케이스 {m['cases']}개)")
    if not rows:
        print("  데이터 없음")
        return m
    print(f"  판정 정확도        {pct(m['accuracy'])}   ({m['correct_total']}/{m['n']})")
    print(f"  놓침(채택→다른)    {rate_str(m['miss'])}")
    print(f"  과잉 수용(유지→채택) {rate_str(m['over_accept'])}")
    print(f"  과잉 보류          {rate_str(m['over_defer'])}")
    print(f"  억지 결론(논의→다른) {rate_str(m['forced'])}")
    print(f"  판정 파싱 실패     {m['parse_fail']}건 | 금지 주장 의심 {m['forbidden_hits']}건 | 토큰 유출 {m['leaks']}건")
    if m["facts"] is not None:
        print(f"  근거 충실도        {pct(m['facts'])}  (필수 사실 포함 비율)")
    print(f"  필수 파일 확인율   {pct(m['req_hit'])} | 탐색 비율 {num(m['explore'], '{:.2f}')}x (읽은 파일/최소 필요)")
    print(f"  건당 토큰 {num(m['tokens'], '{:,.0f}')} | 비용 ${num(m['cost'], '{:.3f}')} | "
          f"시간 {num(m['secs'])}s | 턴 {num(m['turns'])} | 읽은 파일 {num(m['files_read'])}개")
    return m


def confusion(rows):
    cols = VERDICTS + ["파싱실패"]
    mat = {g: collections.Counter() for g in VERDICTS}
    for r in rows:
        mat[r["gold"]][r["pred"] or "파싱실패"] += 1
    short = {ADOPT: "채택", KEEP: "유지", DEFER: "논의", "파싱실패": "실패"}
    print("\n  혼동행렬 (행=정답, 열=예측)")
    print("            " + "".join(f"{short[c]:>6}" for c in cols))
    for g in VERDICTS:
        print(f"  {short[g]:>8}  " + "".join(f"{mat[g][c]:>6}" for c in cols))


def breakdown(rows, title, key):
    groups = collections.defaultdict(list)
    for r in rows:
        groups[key(r)].append(r)
    print(f"\n  [{title}별 정확도]")
    for k in sorted(groups, key=str):
        g = groups[k]
        acc = mean([1.0 if x["correct"] else 0.0 for x in g])
        print(f"    {str(k):<16} {pct(acc)}  (n={len(g)}, 케이스 {len({x['id'] for x in g})})  "
              f"${mean([x['cost'] for x in g]):.3f}/건  {mean([x['secs'] for x in g]):.0f}s")


def per_case_acc(rows):
    d = collections.defaultdict(list)
    for r in rows:
        d[r["id"]].append(1.0 if r["correct"] else 0.0)
    return {k: sum(v) / len(v) for k, v in d.items()}


def print_wrong(rows):
    acc = per_case_acc(rows)
    wrong = [k for k, v in sorted(acc.items()) if v < 1.0]
    if not wrong:
        print("\n  모든 케이스 정답")
        return
    print("\n  [틀린 케이스] (정답 → 예측 분포)")
    for cid in wrong:
        rs = [r for r in rows if r["id"] == cid]
        preds = collections.Counter((r["pred"] or "파싱실패") for r in rs)
        conf = rs[0]["case"]["label_confidence"]
        print(f"    {cid:<3} acc={acc[cid]:.2f} 정답={rs[0]['gold']} → "
              + ", ".join(f"{k}×{v}" for k, v in preds.items()) + f"  [라벨 {conf}]")
    flagged = sorted({r["id"] for r in rows if r["forbidden"]})
    if flagged:
        print("  [금지 주장 의심: 수동 확인] " + ", ".join(flagged))


def compare(name_a, rows_a, name_b, rows_b):
    a, b = metrics(rows_a), metrics(rows_b)
    print(f"\n■ 비교: {name_a}  →  {name_b}")

    def line(label, x, y, fmt, better="high"):
        if x is None or y is None:
            print(f"  {label:<18} {'-':>10} → {'-':>10}")
            return
        d = y - x
        print(f"  {label:<18} {fmt(x):>10} → {fmt(y):>10}   Δ {fmt(d) if fmt is not pct else f'{d * 100:+.1f}%p'}")

    line("판정 정확도", a["accuracy"], b["accuracy"], pct)
    for key, label in [("miss", "놓침"), ("over_accept", "과잉 수용"), ("over_defer", "과잉 보류"), ("forced", "억지 결론")]:
        line(label, a[key][0], b[key][0], pct)
    line("건당 토큰", a["tokens"], b["tokens"], lambda v: f"{v:,.0f}")
    line("건당 비용($)", a["cost"], b["cost"], lambda v: f"{v:.3f}")
    line("건당 시간(s)", a["secs"], b["secs"], lambda v: f"{v:.1f}")

    ca, cb = per_case_acc(rows_a), per_case_acc(rows_b)
    shared = sorted(set(ca) & set(cb))
    up = [(k, ca[k], cb[k]) for k in shared if cb[k] > ca[k]]
    down = [(k, ca[k], cb[k]) for k in shared if cb[k] < ca[k]]
    print(f"\n  케이스별 변화(공통 {len(shared)}개): 개선 {len(up)}, 악화 {len(down)}, 동일 {len(shared) - len(up) - len(down)}")
    for k, x, y in up:
        print(f"    + {k}: {x:.2f} → {y:.2f}")
    for k, x, y in down:
        print(f"    - {k}: {x:.2f} → {y:.2f}")

    if a["n"] and b["n"]:
        d_correct_per_rec = (b["accuracy"] - a["accuracy"])
        d_cost_per_rec = b["cost"] - a["cost"]
        if d_correct_per_rec > 0 and d_cost_per_rec > 0:
            per_correct = d_cost_per_rec / d_correct_per_rec
            print(f"\n  정답 1건을 더 맞추는 데 드는 추가 비용: ${per_correct:.2f}  "
                  f"(추가 비용 ${d_cost_per_rec:.3f}/건 ÷ 정확도 +{d_correct_per_rec * 100:.1f}%p)")
        elif d_correct_per_rec <= 0 and d_cost_per_rec > 0:
            print("\n  정확도 이득 없이 비용만 증가했습니다.")


def judge_facts(records, cases_by_id, view, cache_path, claude_bin, model):
    """필수 사실이 reasoning에 의미상 담겼는지 LLM으로 판정한다. 결과는 캐시 파일에 쌓아 재사용한다."""
    cache = {}
    if cache_path.exists():
        for line in cache_path.read_text(encoding="utf-8").splitlines():
            try:
                c = json.loads(line)
                cache[(c["case_id"], c["run"], c["view"])] = c["coverage"]
            except ValueError:
                pass
    with cache_path.open("a", encoding="utf-8") as out:
        for rec in records:
            if rec.get("error"):
                continue
            key = (rec["case_id"], rec["run"], view)
            if key in cache:
                rec["_facts"] = cache[key]
                continue
            facts = cases_by_id[rec["case_id"]]["gold"]["required_facts"]
            if not facts:
                continue
            r = view_of(rec, view)
            prompt = ("다음은 코드 리뷰 코멘트에 대한 분석 결과다.\n[분석 결과]\n" + text_of(r)
                      + "\n\n[필수 사실 목록]\n" + "\n".join(f"{i + 1}. {f}" for i, f in enumerate(facts))
                      + "\n\n각 필수 사실이 분석 결과에 의미상 담겨 있으면 true, 아니면 false로 판정해라. "
                        "표현이 달라도 같은 내용이면 true다. JSON만 출력한다: {\"covered\":[true,false,...]}")
            try:
                proc = subprocess.run(
                    [claude_bin, "-p", "--output-format", "json", "--model", model, "--tools", "",
                     "--safe-mode", "--strict-mcp-config", "--no-session-persistence",
                     "--system-prompt", "판정자다. 요청한 JSON만 출력한다."],
                    input=prompt, capture_output=True, text=True, encoding="utf-8", timeout=180)
                result = json.loads(proc.stdout)["result"]
                covered = json.loads(extract_json(result))["covered"]
                coverage = sum(1 for c in covered if c) / len(facts)
            except Exception as ex:  # 판정 실패는 건너뛴다
                print(f"  근거 판정 실패 {key}: {str(ex)[:80]}", file=sys.stderr)
                continue
            cache[key] = coverage
            rec["_facts"] = coverage
            out.write(json.dumps({"case_id": key[0], "run": key[1], "view": view, "coverage": coverage}) + "\n")
            out.flush()


def select(rows, headline_only):
    return [r for r in rows if (is_headline(r["case"]) if headline_only else True)]


def report(name, rows, args):
    if args.high_only:
        rows = [r for r in rows if r["case"]["label_confidence"] == "high"]
    print("\n" + "=" * 72)
    print(f" {name}")
    print("=" * 72)
    print_metrics("전체", rows)
    head = select(rows, True)
    print_metrics("헤드라인 (라벨 high · 쌍 제외)", head)
    confusion(rows)
    breakdown(rows, "범주", lambda r: r["case"]["category"])
    breakdown(rows, "cross_file", lambda r: "여러 파일 필요" if r["case"].get("cross_file") else "단일 파일")
    breakdown(rows, "코멘트 형태", lambda r: r["case"]["form"])
    breakdown(rows, "난이도", lambda r: r["case"]["difficulty"])
    breakdown(rows, "라벨 신뢰도", lambda r: r["case"]["label_confidence"])
    print_wrong(rows)
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("results")
    ap.add_argument("--view", choices=["final", "first"], default="final",
                    help="verify 모드 기록에서 어느 관점으로 채점할지 (기본 최종)")
    ap.add_argument("--compare", metavar="OTHER", help="다른 결과 파일과 비교 (results → OTHER)")
    ap.add_argument("--compare-views", action="store_true", help="verify 결과의 1차 vs 최종 쌍체 비교")
    ap.add_argument("--high-only", action="store_true", help="label_confidence=high 케이스만")
    ap.add_argument("--judge-facts", action="store_true", help="근거 충실도를 LLM으로 판정 (추가 호출)")
    ap.add_argument("--judge-model", default="haiku")
    ap.add_argument("--claude-bin", default=os.environ.get("CLAUDE_BIN", "claude"))
    ap.add_argument("--json", metavar="PATH", help="요약 지표를 JSON으로 저장")
    args = ap.parse_args()

    cases = {c["id"]: c for c in be.load()["cases"]}
    recs = load_records(args.results)
    errs = sum(1 for r in recs if r.get("error"))
    if errs:
        print(f"주의: 오류 기록 {errs}건은 채점에서 제외됩니다.")

    if args.judge_facts:
        cache = Path(args.results).with_suffix(".facts.jsonl")
        judge_facts(recs, cases, args.view, cache, args.claude_bin, args.judge_model)

    summary = {}
    rows_a = enrich(recs, cases, "first" if args.compare_views else args.view)
    name_a = Path(args.results).stem + (" [1차]" if args.compare_views else f" [{args.view}]")
    rows_a = report(name_a, rows_a, args)
    summary[name_a] = {k: v for k, v in metrics(rows_a).items()}

    if args.compare_views:
        rows_b = enrich(recs, cases, "final")
        name_b = Path(args.results).stem + " [검증 후]"
        rows_b = report(name_b, rows_b, args)
        changed = [r for r in recs if r.get("changed")]
        print(f"\n  검증자가 판정을 바꾼 건: {len(changed)}/{len(recs)}")
        for r in changed:
            c = cases[r["case_id"]]
            ok_before = r["first"]["verdict"] == c["gold"]["verdict"]
            ok_after = r["verdict"] == c["gold"]["verdict"]
            tag = "바로잡음" if (not ok_before and ok_after) else "망침" if (ok_before and not ok_after) else "둘 다 오답"
            print(f"    {r['case_id']}#{r['run']}: {r['first']['verdict']} → {r['verdict']}  [{tag}]")
        compare(name_a, select(rows_a, False), name_b, select(rows_b, False))
        for sel_name, flt in [("헤드라인", True)]:
            compare(name_a + " (" + sel_name + ")", select(rows_a, flt), name_b + " (" + sel_name + ")", select(rows_b, flt))
        summary[name_b] = {k: v for k, v in metrics(rows_b).items()}

    if args.compare:
        recs_b = load_records(args.compare)
        if args.judge_facts:
            judge_facts(recs_b, cases, args.view, Path(args.compare).with_suffix(".facts.jsonl"),
                        args.claude_bin, args.judge_model)
        rows_b = enrich(recs_b, cases, args.view)
        name_b = Path(args.compare).stem + f" [{args.view}]"
        rows_b = report(name_b, rows_b, args)
        compare(name_a, rows_a, name_b, rows_b)
        compare(name_a + " (헤드라인)", select(rows_a, True), name_b + " (헤드라인)", select(rows_b, True))
        summary[name_b] = {k: v for k, v in metrics(rows_b).items()}

    if args.json:
        Path(args.json).write_text(json.dumps(summary, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
