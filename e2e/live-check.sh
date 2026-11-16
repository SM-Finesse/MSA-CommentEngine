#!/usr/bin/env bash
# 실제 TETR.IO로 새 구조를 한 바퀴 돌려 본다 (live-check 워크플로, 손으로 해 보던 확인을 그대로)
#
#   1. 통계:   gateway → finesse-api → tetris-analytics → TETR.IO
#   2. light: 같은 유저, tetris 칩의 생성 서버(small-gen)가 문장을 만든다
#   3. 추적:   light 요청 하나가 gateway → finesse-api → tetris-analytics · comment-engine 으로 한 추적에 이어지는지
#              (finesse-api는 light 캐시를 다른 스레드에서 만들기 때문에 추적 문맥을 넘겨야 이어진다)
#   4. heavy: 같은 유저의 SSE 스트림. start → chapter 8건 → done, 생성 서버(small-gen)가 각주를 만든다
#   5. 대체 문장: small-gen을 멈추고 light를 다시 부르면 팩의 대체 문장(phrases)이 나온다.
#              heavy는 대체 문장이 없어 챕터가 failed로 온다 (상대가 없을 때의 라이벌 고정 문구만 ok)
#
# 생성 서버가 실제 llm-server면(.env 의 TETRIS_GEN_SERVERS) 문장이 small-gen 것인지는 보지 않고, 5번(생성 서버 멈추기)은 건너뛴다.
# heavy는 ok 챕터가 하나라도 있으면 통과로 보고 챕터별 결과를 그대로 적는다 (실제 LLM은 일부 챕터가 실패할 수 있다).
#
# 본체가 docker compose로 떠 있는 상태에서 실행한다:  bash e2e/live-check.sh <TETR.IO 유저명>
# 실제 TETR.IO를 부른다 (유저 1명에 5번 안팎). 결과는 GITHUB_STEP_SUMMARY가 있으면 거기에도 표로 남긴다.

set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

U=${1:-5han}
G=http://localhost:8080
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}
FAILED=0
# 결과 파일을 두는 곳. 레포 안의 상대 경로로 둔다 — Windows(Git Bash · MSYS2)에서 curl은 /tmp 를 모른다
T=.live-check
mkdir -p "$T"
# python: 요약을 보기 좋게 찍는 데만 쓴다. 없으면(Windows에는 보통 없다) 원본을 그대로 보여 주고 grep으로 확인한다.
# Windows의 python3 는 "Python was not found"만 찍는 가짜일 수 있어서, 실제로 돌려 보고 고른다
PY=
for c in python3 python "py -3"; do
  if $c -c 'print(1)' >/dev/null 2>&1; then PY=$c; break; fi
done
[ -n "$PY" ] || echo "(python 없음 — 요약 대신 응답 원본을 보여 준다)"
raw() { head -c "${2:-1500}" "$1"; echo; }
fail() { printf '실패: %s\n' "$*"; FAILED=1; }

get() { # 경로 파일 → "상태 걸린시간"
  curl -s -o "$2" -w '%{http_code} %{time_total}' "$G$1" || echo "000 0"
}

GEN_SERVERS=$(docker compose config 2>/dev/null | sed -n 's/.*FINESSE_PACK_TETRIS_SERVERS: *//p' | head -1)
case "$GEN_SERVERS" in *small-gen*|"") REAL=0 ;; *) REAL=1 ;; esac
echo "tetris 칩의 생성 서버: ${GEN_SERVERS:-알 수 없음} ($([ "$REAL" = 1 ] && echo '실제 llm-server' || echo '시연용 small-gen'))"

echo "== 1. 통계 ($U)"
read -r code t < <(get "/api/v1/stats/$U" $T/stats.json)
echo "HTTP $code (${t}s)"
[ "$code" = 200 ] || fail "통계가 200이 아님: $(head -c 300 $T/stats.json)"
if [ -z "$PY" ]; then raw "$T/stats.json" 400 | tee "$T/stats-summary.txt"; else
$PY - $T/stats.json <<'PY' | tee $T/stats-summary.txt
import json, sys
s = json.load(open(sys.argv[1]))
picks = (s.get("highlight_judgement") or {}).get("picks") or []
print(f"username={s.get('username')} cold_start={s.get('cold_start')} match_count={s.get('match_count')} updated_at={s.get('updated_at')}")
print("하이라이트 후보(calc): " + (", ".join(f"{p.get('key')}({p.get('zone')})" for p in picks) or "없음"))
PY
fi

echo "== 2. light"
read -r code t < <(get "/api/v1/comment/$U?scope=light" $T/light1.json)
echo "HTTP $code (${t}s)"
[ "$code" = 200 ] || fail "light가 200이 아님: $(head -c 300 $T/light1.json)"
if [ -z "$PY" ]; then raw "$T/light1.json" | tee "$T/light1.txt"; else
$PY - $T/light1.json <<'PY' | tee $T/light1.txt
import json, sys
j = json.load(open(sys.argv[1]))
print("light_summary: " + str(j.get("light_summary")))
for h in j.get("highlights") or []:
    print(f"  {h.get('stat')}: {h.get('sentence')}")
PY
fi
if grep -q '"highlights":\[\]' $T/light1.json; then
  echo "(하이라이트 없음 — 콜드스타트이거나 고른 지표가 없어 생성 서버를 부르지 않음)"
elif [ "$REAL" = 0 ] && ! grep -q 'small-gen:9200' $T/light1.json; then
  fail "small-gen 문장이 아님 (생성 서버 경로를 타지 않았음)"
fi

echo "== 3. 추적 (light 요청 하나가 한 줄로)"
if [ -z "$PY" ]; then
  TRACE="(python이 없어 건너뜀 — 추적 화면 http://localhost:9411 에서 직접 본다)"
else
TRACE=$($PY - <<'PY'
import json, time, urllib.request
want = {"gateway", "finesse-api", "tetris-analytics", "comment-engine"}
seen = []
for _ in range(15):
    try:
        traces = json.load(urllib.request.urlopen("http://localhost:9411/api/v2/traces?serviceName=finesse-api&limit=100"))
    except Exception:
        traces = []
    for t in traces:
        names = {s.get("localEndpoint", {}).get("serviceName") for s in t} - {None}
        if want <= names:
            print(f"OK trace {t[0]['traceId']} spans={len(t)} services={sorted(names)}")
            raise SystemExit(0)
        seen.append(sorted(names))
    time.sleep(2)
print("NONE 본 추적: " + json.dumps(sorted({tuple(x) for x in seen})[:10], ensure_ascii=False))
PY
)
fi
echo "$TRACE"
case "$TRACE" in OK*|"(python"*) ;; *) fail "gateway → finesse-api → tetris-analytics · comment-engine 이 한 추적에 없음" ;; esac

# SSE 본문 파일을 읽어 요약한다. 인자: 파일, 기대(all-ok | real | gen-down)
heavy_summary() {
  if [ -z "$PY" ]; then
    local n
    n=$(grep -c '^event:chapter' "$1")
    echo "이벤트: start×$(grep -c '^event:start' "$1") chapter×$n done×$(grep -c '^event:done' "$1") error×$(grep -c '^event:error' "$1")"
    grep '^data:' "$1" | cut -c1-300
    [ "$n" = 8 ] || echo "BAD chapter가 정확히 8건이 아님 (${n}건)"
    grep -q '^event:done' "$1" || echo "BAD done 이벤트가 없음"
    if [ "$2" != gen-down ] && ! grep -q '"status":"ok"' "$1" && ! grep -q '"attempt_count":0' "$1"; then
      echo "BAD ok 챕터가 하나도 없음"
    fi
    return
  fi
  $PY - "$1" "$2" <<'PY'
import json, sys
body = open(sys.argv[1], encoding="utf-8").read()
expect = sys.argv[2]
events = []
for block in body.split("\n\n"):
    name, data = None, ""
    for line in block.split("\n"):
        if line.startswith("event:"):
            name = line[6:].strip()
        elif line.startswith("data:"):
            data += line[5:].strip()
    if name:
        events.append((name, json.loads(data)))
names = [n for n, _ in events]
chapters = [d for n, d in events if n == "chapter"]
print("이벤트: " + " ".join(f"{n}×{names.count(n)}" for n in dict.fromkeys(names)))
for c in chapters:
    print(f"  {c.get('chapter_id')}: {c.get('status')} {c.get('footnote') or ''}")
done = events[-1][1] if events and events[-1][0] == "done" else None
print("done: " + json.dumps(done, ensure_ascii=False))
bad = []
if not names or names[0] != "start":
    bad.append("첫 이벤트가 start가 아님")
if done is None:
    bad.append("마지막 이벤트가 done이 아님")
if len(chapters) != 8 or len({c.get("chapter_id") for c in chapters}) != 8:
    bad.append(f"chapter가 정확히 8건이 아님 ({len(chapters)}건)")
ok = [c for c in chapters if c.get("status") == "ok"]
cold = bool(chapters) and all(c.get("status") == "failed" and c.get("attempt_count") == 0 for c in chapters)
if expect == "all-ok" and not cold:
    if len(ok) != 8:
        bad.append(f"ok 챕터가 8개가 아님 ({len(ok)}개)")
    if not any("small-gen:9200" in (c.get("footnote") or "") for c in ok):
        bad.append("small-gen 각주가 없음 (생성 서버 경로를 타지 않았음)")
if expect == "real" and not cold and not ok:
    bad.append("ok 챕터가 하나도 없음")
if expect == "gen-down":
    if any("small-gen" in (c.get("footnote") or "") for c in chapters):
        bad.append("small-gen을 멈췄는데 small-gen 각주가 나옴")
    if any(c.get("status") == "ok" and c.get("chapter_id") != "rivals" for c in chapters):
        bad.append("생성 서버가 없는데 ok인 챕터가 있음")
if cold:
    print("(콜드스타트 — 엔진을 부르지 않고 8챕터 모두 failed)")
for b in bad:
    print("BAD " + b)
PY
}

echo "== 4. heavy SSE"
read -r code t < <(get "/api/v1/comment/$U?scope=heavy" $T/heavy1.txt)
echo "HTTP $code (${t}s)"
[ "$code" = 200 ] || fail "heavy가 200이 아님: $(head -c 300 $T/heavy1.txt)"
heavy_summary $T/heavy1.txt "$([ "$REAL" = 1 ] && echo real || echo all-ok)" | tee $T/heavy1-summary.txt
if grep -q '^BAD ' $T/heavy1-summary.txt; then fail "heavy 스트림이 기대와 다름"; fi

if [ "$REAL" = 1 ]; then
  echo "== 5. 건너뜀 (실제 llm-server는 멈추지 않는다)"
  echo "(실제 llm-server라 건너뜀)" > $T/light2.txt; : > $T/heavy2-summary.txt
else
echo "== 5. small-gen을 멈추고 대체 문장 (finesse-api를 다시 띄워 light · heavy 캐시를 비움)"
docker compose stop small-gen >/dev/null 2>&1
docker compose restart finesse-api >/dev/null 2>&1
docker compose up -d --no-deps --wait --wait-timeout 120 finesse-api >/dev/null 2>&1 || fail "finesse-api가 다시 뜨지 않음"
sleep 3
read -r code t < <(get "/api/v1/comment/$U?scope=light" $T/light2.json)
echo "HTTP $code (${t}s)"
[ "$code" = 200 ] || fail "대체 문장 light가 200이 아님: $(head -c 300 $T/light2.json)"
if [ -z "$PY" ]; then raw "$T/light2.json" | tee "$T/light2.txt"; else
$PY - $T/light2.json <<'PY' | tee $T/light2.txt
import json, sys
j = json.load(open(sys.argv[1]))
print("light_summary: " + str(j.get("light_summary")))
for h in j.get("highlights") or []:
    print(f"  {h.get('stat')}: {h.get('sentence')}")
PY
fi
if grep -q 'small-gen' $T/light2.json; then fail "small-gen을 멈췄는데 small-gen 문장이 나옴"; fi

read -r code t < <(get "/api/v1/comment/$U?scope=heavy" $T/heavy2.txt)
echo "heavy: HTTP $code (${t}s)"
[ "$code" = 200 ] || fail "생성 서버가 없을 때 heavy가 200이 아님: $(head -c 300 $T/heavy2.txt)"
heavy_summary $T/heavy2.txt gen-down | tee $T/heavy2-summary.txt
if grep -q '^BAD ' $T/heavy2-summary.txt; then fail "생성 서버가 없을 때 heavy 스트림이 기대와 다름"; fi
docker compose start small-gen >/dev/null 2>&1
fi

{
  echo "## 새 구조 한 바퀴 (\`$U\`, 실제 TETR.IO)"
  echo
  echo "### 1. 통계"
  echo '```'; cat $T/stats-summary.txt; echo '```'
  echo "### 2. light (생성 서버 small-gen)"
  echo '```'; cat $T/light1.txt; echo '```'
  echo "### 3. 추적"
  echo '```'; echo "$TRACE"; echo '```'
  echo "### 4. heavy SSE (생성 서버 small-gen)"
  echo '```'; cat $T/heavy1-summary.txt; echo '```'
  echo "### 5. small-gen을 멈췄을 때 (light는 팩 대체 문장, heavy는 챕터 failed)"
  echo '```'; cat $T/light2.txt; cat $T/heavy2-summary.txt; echo '```'
} >> "$SUMMARY"

if [ "$FAILED" = 0 ]; then echo "== 통과"; else echo "== 실패한 확인이 있음"; fi
exit "$FAILED"
