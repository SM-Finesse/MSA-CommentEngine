#!/usr/bin/env bash
# 회로 차단기 열림·닫힘 (finesse-api → 엔진 구간, Resilience4j)
#
#   1. 회로는 닫혀 있다 (CLOSED). gateway → finesse-api → 엔진 호출은 200
#   2. 엔진을 멈추고 부른다 → 503. 엔진에 닿지 않는 일이 쌓이면 회로가 열린다 (OPEN)
#   3. 열린 동안에는 엔진을 부르지 않고 바로 503 (Retry-After 10)
#   4. 엔진을 다시 띄우고 열림 시간(10초)이 지나면 반열림(HALF_OPEN) → 3번 성공하면 닫힌다 (CLOSED)
#
# 본체가 docker compose로 떠 있는 상태에서 실행한다:  bash e2e/circuit-breaker.sh
# 확인한 환경: GitHub Actions (ubuntu). Windows Git Bash에서는 돌려보지 않았다.

set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

URL=http://localhost:8080/api/v1/comment-demo/light
HEADERS=$(mktemp)
CODE=
TIME=
RETRY=

step() { printf '\n== %s\n' "$*"; }
fail() { printf '\n실패: %s\n' "$*" >&2; exit 1; }

# gateway → finesse-api → 엔진 호출. 결과는 $CODE(HTTP 코드), $TIME(걸린 초), $RETRY(Retry-After)
call() {
  local out
  : > "$HEADERS"
  out=$(curl -s -o /dev/null -D "$HEADERS" -w '%{http_code} %{time_total}' \
    -H 'Content-Type: application/json' --data-binary @e2e/demo-light-api.json "$URL") || out="000 0"
  CODE=${out% *}
  TIME=${out#* }
  RETRY=$(tr -d '\r' < "$HEADERS" | awk 'tolower($1) == "retry-after:" {print $2}')
}

# finesse-api 헬스에서 회로 상태를 읽는다. 관리 포트(4004)는 밖으로 열려 있지 않아 컨테이너 안에서 부른다
state() {
  docker compose exec -T finesse-api bash -c \
    "exec 3<>/dev/tcp/localhost/4004 && printf 'GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3 && cat <&3" \
    2>/dev/null | grep -o '"state":"[A-Z_]*"' | head -1 | cut -d'"' -f4 || true
}

restore_engine() { docker compose up -d --no-deps --wait --wait-timeout 120 comment-engine >/dev/null 2>&1 || true; }

step "1. 회로는 닫혀 있고 호출은 200"
s=$(state)
echo "회로: $s"
[ "$s" = CLOSED ] || fail "회로가 CLOSED가 아님"
call
echo "HTTP $CODE (${TIME}초)"
[ "$CODE" = 200 ] || fail "200이 아님"

step "2. 엔진을 멈추고 부른다"
docker compose stop comment-engine >/dev/null
trap restore_engine EXIT   # 중간에 실패해도 엔진은 되살린다
opened=
for i in $(seq 1 6); do
  call
  s=$(state)
  echo "try $i: HTTP $CODE (${TIME}초) Retry-After=${RETRY:-없음} 회로=$s"
  [ "$CODE" = 503 ] || fail "503이 아님"
  if [ "$s" = OPEN ]; then opened=$i; break; fi
done
[ -n "$opened" ] || fail "회로가 열리지 않음"

step "3. 열린 동안에는 엔진을 부르지 않고 바로 503"
call
echo "HTTP $CODE (${TIME}초) Retry-After=${RETRY:-없음}"
[ "$CODE" = 503 ] || fail "503이 아님"
[ "$RETRY" = 10 ] || fail "Retry-After가 10(회로가 열려 있는 시간)이 아님"
awk -v t="$TIME" 'BEGIN { exit !(t < 0.5) }' || fail "바로 실패하지 않음 (${TIME}초)"

step "4. 엔진을 다시 띄우고 회로가 열림에서 벗어나길 기다린다"
docker compose up -d --no-deps --wait --wait-timeout 120 comment-engine >/dev/null
for _ in $(seq 1 20); do
  s=$(state)
  if [ "$s" != OPEN ]; then break; fi
  sleep 1
done
echo "회로: $s"
for i in 1 2 3; do
  call
  echo "try $i: HTTP $CODE (${TIME}초)"
  [ "$CODE" = 200 ] || fail "200이 아님"
done
s=$(state)
echo "회로: $s"
[ "$s" = CLOSED ] || fail "회로가 다시 닫히지 않음"

step "통과: 엔진에 닿지 않으면 회로가 열려 바로 503, 엔진이 돌아오면 닫혀 다시 200"
