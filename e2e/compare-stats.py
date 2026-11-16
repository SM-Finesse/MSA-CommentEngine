#!/usr/bin/env python3
"""모놀리식 backend와 MSA(gateway 경유)의 응답 비교 (compare-monolith 워크플로)

1. GET /api/v1/stats/{username}
   유저마다 모놀리식을 먼저 부르고(실제 TETR.IO, 프록시가 기억) MSA를 나중에 부른다(프록시가 재생).
   같은 TETR.IO 데이터에서 나온 두 응답이 같은지 본다. updated_at(계산한 시각)만 빼고 비교한다.
2. GET /api/v1/comment/{username}?scope=light (stats는 양쪽 캐시에 이미 있음)
   CI에는 LLM 서버가 없어서 양쪽 다 대체 문장이 나온다 (모놀리식 LightCommentTemplates, MSA tetris 칩 phrases).
   - 하이라이트 지표와 순서: 같아야 한다 (calc가 고른 것을 같은 규칙으로 뽑음)
   - 지표별 문장: 앞의 비교 표현(모놀리식은 5개 중 무작위, 칩은 "상대 대비")을 떼고 같아야 한다
     값 표기는 delta_comeback만 다를 수 있다 (모놀리식은 원값, 칩은 2자리)
   - 기본 코멘트: 콜드스타트 안내는 글자까지 같아야 하고, 나머지는 문체가 달라(모놀리식 끝말 바꾸기) 비교하지 않는다
   - attempt_count는 MSA에 없다 (알려진 차이)
3. GET /api/v1/comment/{username}?scope=heavy (SSE)
   CI에는 LLM 서버가 없어서 양쪽 다 챕터가 failed로 온다 (상대가 없을 때의 라이벌 고정 문구만 ok).
   - 이벤트 모양: start(total_chapters) → chapter 정확히 8건(chapter_id 중복 없음) → done
   - 챕터별 status와 footnote, done의 completed와 failed_chapters가 같아야 한다
   - stats 실패(없는 유저 등)는 error 이벤트의 error_code · message가 같아야 한다
   - attempt_count는 비교하지 않는다 (MSA는 엔진을 거친 챕터에 없음, 알려진 차이). elapsed_ms도 비교하지 않는다

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
# 모놀리식 LightCommentTemplates.COMPARE_PHRASES (31번 1.3절)
COMPARE_PHRASES = ("상대 대비", "맞붙은 상대들에 비해", "최근 대전 상대들보다", "대전 상대 평균 대비", "매 경기 상대와 견줘 보면")
COLD_START = "최근 매치 데이터가 부족해 하이라이트를 표시할 수 없습니다."


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


def split_sentence(sentence):
    """비교 표현을 떼고 (본문, 값 괄호)로 나눈다"""
    for p in COMPARE_PHRASES:
        if sentence.startswith(p + " "):
            sentence = sentence[len(p) + 1:]
            break
    i = sentence.rfind("(")
    return (sentence, "") if i < 0 else (sentence[:i], sentence[i:])


def compare_light(u):
    """(같은지, 메모 목록, 출력할 줄 목록)"""
    s1, b1, t1 = get(f"{MONO}/api/v1/comment/{u}?scope=light")
    s2, b2, t2 = get(f"{MSA}/api/v1/comment/{u}?scope=light")
    out = [f"  light: 모놀리식 HTTP {s1} {t1:.1f}s / MSA HTTP {s2} {t2:.1f}s"]
    notes = []
    if s1 != s2:
        out.append(f"    상태가 다름: {b1[:300]!r} / {b2[:300]!r}")
        return False, ["상태 코드가 다름"], out
    if s1 != 200:
        same = parse(b1) == parse(b2)
        if not same:
            out.append(f"    본문이 다름: {b1[:300]!r} / {b2[:300]!r}")
        return same, [] if same else ["에러 본문이 다름"], out
    j1, j2 = json.loads(b1), json.loads(b2)
    h1, h2 = j1.get("highlights") or [], j2.get("highlights") or []
    stats1, stats2 = [h["stat"] for h in h1], [h["stat"] for h in h2]
    same = stats1 == stats2
    out.append(f"    하이라이트: 모놀리식 {stats1} / MSA {stats2}")
    if not same:
        notes.append("하이라이트 지표가 다름")
    for a, b in zip(h1, h2):
        if a["stat"] != b["stat"]:
            continue
        (body1, val1), (body2, val2) = split_sentence(a["sentence"]), split_sentence(b["sentence"])
        if body1 != body2:
            same = False
            notes.append(f"{a['stat']} 문장이 다름")
            out.append(f"    {a['stat']}: 모놀리식 {a['sentence']!r}\n{'':>{len(a['stat']) + 6}}MSA     {b['sentence']!r}")
        elif val1 != val2:
            if a["stat"] == "delta_comeback":
                notes.append(f"delta_comeback 값 표기 {val1} / {val2} (알려진 차이)")
            else:
                same = False
                notes.append(f"{a['stat']} 값 표기가 다름 {val1} / {val2}")
    if not h1 and not h2:
        if j1.get("light_summary") == COLD_START or j2.get("light_summary") == COLD_START:
            if j1.get("light_summary") != j2.get("light_summary"):
                same = False
                notes.append("콜드스타트 안내가 다름")
        else:
            notes.append(f"하이라이트 0개: 모놀리식 {j1.get('light_summary')!r} / MSA {j2.get('light_summary')!r}")
    elif not j1.get("light_summary") or not j2.get("light_summary"):
        same = False
        notes.append("기본 코멘트가 비어 있음")
    out.append(f"    기본 코멘트(비교 안 함): 모놀리식 {j1.get('light_summary')!r} / MSA {j2.get('light_summary')!r}")
    return same, notes, out


def sse(url):
    """SSE 스트림을 끝까지 읽어 (상태, [(이벤트 이름, data)], 걸린 시간)"""
    status, body, took = get(url)
    events = []
    for block in body.decode("utf-8", errors="replace").split("\n\n"):
        name, data = None, ""
        for line in block.split("\n"):
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                data += line[5:].strip()
        if name:
            try:
                events.append((name, json.loads(data)))
            except ValueError:
                events.append((name, data))
    return status, events, took


def heavy_view(events):
    """비교할 것만 추린다"""
    chapters = {d.get("chapter_id"): (d.get("status"), d.get("footnote")) for n, d in events if n == "chapter"}
    done = next((d for n, d in events if n == "done"), None)
    error = next((d for n, d in events if n == "error"), None)
    return {
        "names": [n for n, _ in events if n != "chapter"],
        "chapter_count": sum(1 for n, _ in events if n == "chapter"),
        "chapters": chapters,
        "done": None if done is None else {"completed": done.get("completed"),
                                           "failed_chapters": sorted(done.get("failed_chapters") or [])},
        "error": None if error is None else {"error_code": error.get("error_code"), "message": error.get("message")},
        "start": next((d for n, d in events if n == "start"), None),
    }


def compare_heavy(u):
    """(같은지, 메모 목록, 출력할 줄 목록)"""
    s1, e1, t1 = sse(f"{MONO}/api/v1/comment/{u}?scope=heavy")
    s2, e2, t2 = sse(f"{MSA}/api/v1/comment/{u}?scope=heavy")
    out = [f"  heavy: 모놀리식 HTTP {s1} {t1:.1f}s / MSA HTTP {s2} {t2:.1f}s"]
    if s1 != s2:
        return False, ["상태 코드가 다름"], out
    v1, v2 = heavy_view(e1), heavy_view(e2)
    notes = []
    for key, label in (("names", "이벤트 순서"), ("start", "start"), ("chapter_count", "chapter 건수"),
                       ("chapters", "챕터 status · footnote"), ("done", "done"), ("error", "error 이벤트")):
        if v1[key] != v2[key]:
            notes.append(f"{label}가 다름")
            out.append(f"    {label}: 모놀리식 {json.dumps(v1[key], ensure_ascii=False)}")
            out.append(f"    {'':>{len(label)}}  MSA      {json.dumps(v2[key], ensure_ascii=False)}")
    if v2["error"] is None and (v2["chapter_count"] != 8 or len(v2["chapters"]) != 8):
        notes.append(f"MSA chapter가 정확히 8건이 아님 ({v2['chapter_count']}건)")
    if v2["error"] is not None:
        out.append(f"    error: {v2['error']['error_code']}")
    else:
        statuses = [v2["chapters"][c][0] for c in v2["chapters"]]
        out.append("    챕터: " + ", ".join(f"{c}={v2['chapters'][c][0]}" for c in v2["chapters"]))
        out.append(f"    done: {json.dumps(v2['done'], ensure_ascii=False)} (ok {statuses.count('ok')})")
    return not notes, notes, out


def main():
    if not USERS:
        print("USERS가 비어 있음")
        return 2
    rows, light_rows, heavy_rows, bad = [], [], [], 0
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

        lsame, lnotes, lout = compare_light(u)
        print("\n".join(lout))
        print(f"  light: {'같음' if lsame else '다름'}" + (f" ({'; '.join(lnotes)})" if lnotes else ""))
        light_rows.append((u, "같음" if lsame else "다름", "; ".join(lnotes)))
        if not lsame:
            bad += 1

        hsame, hnotes, hout = compare_heavy(u)
        print("\n".join(hout))
        print(f"  heavy: {'같음' if hsame else '다름'}" + (f" ({'; '.join(hnotes)})" if hnotes else ""))
        heavy_rows.append((u, "같음" if hsame else "다름", "; ".join(hnotes)))
        if not hsame:
            bad += 1

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## 모놀리식 vs MSA 통계 응답 비교\n\n")
            f.write("| 유저 | 결과 | 모놀리식 | MSA | TETR.IO 실제 호출 | MSA 재생 | 메모 |\n|---|---|---|---|---|---|---|\n")
            for u, v, s1, s2, mf, mr, xf, t1, t2, n in rows:
                f.write(f"| `{u}` | {v} | {s1} ({t1:.1f}s) | {s2} ({t2:.1f}s) | {mf} | {mr} | {n or '-'} |\n")
            f.write(f"\n비교에서 뺀 필드: {', '.join(VOLATILE)}\n")
            f.write("\n## light (양쪽 다 대체 문장)\n\n| 유저 | 결과 | 메모 |\n|---|---|---|\n")
            for u, v, n in light_rows:
                f.write(f"| `{u}` | {v} | {n or '-'} |\n")
            f.write("\n하이라이트 지표·순서와 지표별 문장(비교 표현을 뗀 것)을 본다. 기본 코멘트는 문체가 달라 비교하지 않는다\n")
            f.write("\n## heavy SSE (양쪽 다 LLM 서버 없음)\n\n| 유저 | 결과 | 메모 |\n|---|---|---|\n")
            for u, v, n in heavy_rows:
                f.write(f"| `{u}` | {v} | {n or '-'} |\n")
            f.write("\n이벤트 순서, 챕터별 status · footnote, done의 completed · failed_chapters, error 이벤트를 본다. "
                    "attempt_count와 elapsed_ms는 비교하지 않는다\n")
    total = len(USERS) * 3
    print(f"\n{total - bad}/{total} 같음 (stats {len(USERS)}건 + light {len(USERS)}건 + heavy {len(USERS)}건)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
