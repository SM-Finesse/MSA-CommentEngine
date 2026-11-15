#!/usr/bin/env bash
# 화면의 통계 경로가 gateway → finesse-api → tetris-analytics 로 이어지고, 응답을 고치지 않고 넘기는지
#
#   1. 틀린 유저명이면 tetris-analytics(모놀리식 코드)의 400 INVALID_USERNAME 이 그대로 온다
#   2. 쿼리도 그대로 넘어간다: refresh=abc 는 tetris-analytics 가 400 BAD_REQUEST 로 답한다
#   3. tetris-analytics 를 멈추면 finesse-api 가 502 TETRIO_API_UNAVAILABLE + Retry-After 로 답한다
#   4. 다시 띄우면 다시 1과 같은 응답
#
# 실제 TETR.IO 는 부르지 않는다 (유저명 검사와 파라미터 검사는 TETR.IO 를 부르기 전에 끝난다).
# 같은 유저의 실제 통계를 모놀리식과 비교하는 것은 .github/workflows/compare-monolith.yml (수동 실행).
#
# 본체가 docker compose로 떠 있는 상태에서 실행한다:  bash e2e/stats-relay.sh
# 확인한 환경: GitHub Actions (ubuntu). Windows Git Bash에서는 돌려보지 않았다.

set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

URL=http://localhost:8080/api/v1/stats
HEADERS=$(mktemp)
BODY=$(mktemp)
trap 'rm -f "$HEADERS" "$BODY"' EXIT

fail() { printf '\n실패: %s\n' "$*" >&2; exit 1; }

# 부르고 CODE · TIME · RETRY 를 채운다
call() {
  local out
  out=$(curl -s -o "$BODY" -D "$HEADERS" -w '%{http_code} %{time_total}' "$URL/$1") || out="000 0"
  CODE=${out% *}
  TIME=${out#* }
  RETRY=$(tr -d '\r' < "$HEADERS" | awk 'tolower($1) == "retry-after:" {print $2}')
  echo "GET /api/v1/stats/$1 -> HTTP $CODE (${TIME}s) Retry-After=${RETRY:--}  $(cat "$BODY")"
}

# 본문이 이 JSON 과 같은지 (키 순서·공백과 상관없이)
same_json() {
  python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1])) == json.loads(sys.argv[2]) else 1)' "$BODY" "$1"
}

# 본문의 error_code
code_of() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("error_code", ""))' "$BODY" 2>/dev/null || true
}

INVALID='{"error_code":"INVALID_USERNAME","message":"유저명은 3~16자의 영문·숫자·_·-만 쓸 수 있습니다"}'

restore() { docker compose up -d --no-deps --wait --wait-timeout 180 tetris-analytics >/dev/null 2>&1 || true; }

echo "== 1. 틀린 유저명"
call a
[ "$CODE" = 400 ] || fail "400 이 아님"
same_json "$INVALID" || fail "모놀리식과 다른 본문"

echo "== 2. 쿼리도 그대로"
call 'abc?refresh=abc'
[ "$CODE" = 400 ] || fail "400 이 아님"
[ "$(code_of)" = BAD_REQUEST ] || fail "tetris-analytics 의 BAD_REQUEST 가 아님 (finesse-api 가 쿼리를 바꿨거나 먼저 막음)"

echo "== 3. tetris-analytics 를 멈춘다"
docker compose stop tetris-analytics >/dev/null
trap 'restore; rm -f "$HEADERS" "$BODY"' EXIT
call abc
[ "$CODE" = 502 ] || fail "502 가 아님"
same_json '{"error_code":"TETRIO_API_UNAVAILABLE","message":"일시적으로 조회할 수 없습니다, 잠시 후 다시 시도"}' \
  || fail "모놀리식과 같은 코드·문장이 아님"
[ "$RETRY" = 5 ] || fail "Retry-After 가 5 가 아님"

echo "== 4. 다시 띄운다"
docker compose up -d --no-deps --wait --wait-timeout 180 tetris-analytics >/dev/null
for _ in $(seq 1 15); do
  call a
  if [ "$CODE" = 400 ]; then break; fi
  sleep 2
done
[ "$CODE" = 400 ] || fail "다시 띄운 뒤에도 tetris-analytics 에 닿지 않음"
same_json "$INVALID" || fail "모놀리식과 다른 본문"

echo "== 통과: 통계 응답을 tetris-analytics 에서 그대로 넘긴다"
