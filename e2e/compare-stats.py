#!/usr/bin/env python3
"""모놀리식 backend와 MSA(gateway 경유)의 GET /api/v1/stats/{username} 응답 비교 (compare-monolith 워크플로)

유저마다 모놀리식을 먼저 부르고(실제 TETR.IO, 프록시가 기억) MSA를 나중에 부른다(프록시가 재생).
같은 TETR.IO 데이터에서 나온 두 응답이 같은지 본다. updated_at(계산한 시각)만 빼고 비교한다.

환경변수: USERS (쉼표로), MONO (기본 http://localhost:8081), MSA (기본 http://localhost:8080),
          PROXY (기본 http://localhost:9500)
"""
import difflib
import json
import os
import sys
import time
import urllib.error
import urllib.request

USERS = [u.strip() for u in os.environ.get("USERS", "").split(",") if u.strip()]
MONO = os.environ.get("MONO", "http://localhost:8081")
MSA = os.environ.get("MSA", "http://localhost:8080")
PROXY = os.environ.get("PROXY", "http://localhost:9500")
VOLATILE = ("updated_at",)


def counts():
    return json.load(urllib.request.urlopen(PROXY + "/__stats", timeout=10))["counts"]


def get(url):
    started = time.monotonic()
    try:
        with urllib.request.urlopen(url, timeout=90) as r:
            return r.status, r.read(), time.monotonic() - started
    except urllib.error.HTTPError as e:
        return e.code, e.read(), time.monotonic() - started


def parse(body):
    try:
        j = json.loads(body)
    except ValueError:
        return None
    if isinstance(j, dict):
        for k in VOLATILE:
            j.pop(k, None)
    return j


def lines(j, raw):
    if j is None:
        return raw.decode("utf-8", errors="replace").splitlines()
    return json.dumps(j, ensure_ascii=False, indent=1, sort_keys=True).splitlines()


def main():
    if not USERS:
        print("USERS가 비어 있음")
        return 2
    rows, bad = [], 0
    for u in USERS:
        c0 = counts()
        s1, b1, t1 = get(f"{MONO}/api/v1/stats/{u}")
        c1 = counts()
        s2, b2, t2 = get(f"{MSA}/api/v1/stats/{u}")
        c2 = counts()
        mono_fwd, mono_rep = c1["forward"] - c0["forward"], c1["replay"] - c0["replay"]
        msa_fwd, msa_rep = c2["forward"] - c1["forward"], c2["replay"] - c1["replay"]
        j1, j2 = parse(b1), parse(b2)

        notes = []
        same = s1 == s2 and j1 is not None and j1 == j2
        if mono_fwd + mono_rep == 0:
            notes.append("모놀리식이 프록시를 거치지 않음 (설정 확인)")
            same = False
        if mono_fwd > 0 and msa_rep == 0:
            notes.append("MSA가 프록시를 거치지 않음 (설정 확인)")
            same = False
        if msa_fwd > 0:
            notes.append(f"MSA 요청 {msa_fwd}건이 모놀리식과 달라 실제 TETR.IO로 감")

        verdict = "같음" if same else "다름"
        rows.append((u, verdict, s1, s2, mono_fwd, msa_rep, msa_fwd, t1, t2, "; ".join(notes)))
        print(f"{verdict}  {u}: 모놀리식 HTTP {s1} {t1:.1f}s (TETR.IO 실제 호출 {mono_fwd}) / "
              f"MSA HTTP {s2} {t2:.1f}s (재생 {msa_rep}, 실제 호출 {msa_fwd})")
        for n in notes:
            print(f"  - {n}")
        if not same:
            bad += 1
            diff = list(difflib.unified_diff(lines(j1, b1), lines(j2, b2), "monolith", "msa", lineterm=""))
            print("\n".join(diff[:300]) or "(본문은 같고 상태 코드만 다름)")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## 모놀리식 vs MSA 통계 응답 비교\n\n")
            f.write("| 유저 | 결과 | 모놀리식 | MSA | TETR.IO 실제 호출 | MSA 재생 | 메모 |\n|---|---|---|---|---|---|---|\n")
            for u, v, s1, s2, mf, mr, xf, t1, t2, n in rows:
                f.write(f"| `{u}` | {v} | {s1} ({t1:.1f}s) | {s2} ({t2:.1f}s) | {mf} | {mr} | {n or '-'} |\n")
            f.write(f"\n비교에서 뺀 필드: {', '.join(VOLATILE)}\n")
    print(f"\n{len(USERS) - bad}/{len(USERS)} 같음")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
