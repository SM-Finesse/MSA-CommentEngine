#!/usr/bin/env bash
# 칩 꽂기 시험: 본체를 한 줄도 고치지 않고, 자기 서비스를 가진 칩을 폴더 하나로 꽂고 뽑을 수 있는가
#
# 본체와 tetris 칩이 docker compose로 떠 있는 상태에서 실행한다.
#   bash e2e/plug-test.sh
#
#   1. 꽂기 전: 새 칩(sample)의 팩도 경로도 없다 (gateway 경유 404 두 번)
#   2. 꽂기: 칩 폴더 하나(e2e/chips/sample)를 chips/ 에 복사한다 — 팩, 서비스(compose.yml), gateway 경로가 다 들어 있다
#   3. 꽂기: 칩의 서비스(생성 서버 + API 서비스)를 띄우고, 본체(엔진 · gateway)는 재시작만 한다 (이미지 재빌드 없음)
#   4. gateway 경유로 새 칩이 돈다: 팩(엔진 직접), 칩의 API 경로(/sample/**), gateway 헬스에 칩의 서비스.
#      기존 칩(tetris, demo)은 원래대로 돈다
#   5. 본체 · 슬롯 · 다른 칩은 바뀌지 않았다 (git status). 바뀐 곳은 chips/sample/ 뿐
#   6. 뽑기: 칩의 서비스를 내리고 폴더를 치운 뒤 본체를 재시작하면 팩도 경로도 사라진다
#
# e2e/chips/sample 은 이 시험에만 쓰는 칩이다. 실제 칩이 아니다.
# 확인한 환경: GitHub Actions (ubuntu). Windows Git Bash에서는 돌려보지 않았다.

set -euo pipefail
export MSYS_NO_PATHCONV=1   # Windows Git Bash가 /app 같은 인자를 C:/... 로 바꾸지 않게
cd "$(dirname "$0")/.."

GW=http://localhost:8080
CHIP=sample
GEN_PORT=9300
# 꽂은 뒤의 compose 묶음: 지금 떠 있는 것(.env 의 COMPOSE_FILE) + 새 칩의 compose
export COMPOSE_PATH_SEPARATOR=:
BASE_FILES=$(sed -n 's/^COMPOSE_FILE=//p' .env)
[ -n "$BASE_FILES" ] || { echo ".env 에 COMPOSE_FILE 이 없음" >&2; exit 1; }
PLUGGED_FILES="$BASE_FILES:chips/$CHIP/compose.yml"
plugged() { COMPOSE_FILE="$PLUGGED_FILES" docker compose "$@"; }
BODY=
CODE=

step() { printf '\n== %s\n' "$*"; }
fail() { printf '\n실패: %s\n' "$*" >&2; exit 1; }
show() { printf '%s %s\n' "$CODE" "$BODY"; }
expect() { case "$BODY" in *"$1"*) ;; *) fail "응답에 '$1' 이(가) 없음";; esac; }

# gateway 경유 호출. 결과는 $CODE, $BODY 에 담는다. 세 번째 인자는 JSON 또는 @파일
call() {
  local out
  if [ $# -ge 3 ]; then
    out=$(curl -sS -w '\n%{http_code}' -X "$1" -H 'Content-Type: application/json' --data-binary "$3" "$GW$2") || out=$'\n000'
  else
    out=$(curl -sS -w '\n%{http_code}' -X "$1" "$GW$2") || out=$'\n000'
  fi
  BODY=${out%$'\n'*}
  CODE=${out##*$'\n'}
}

light() {
  call POST /v1/comments/light \
    "{\"domain\":\"$1\",\"items\":[{\"metric\":\"$2\",\"value\":$3,\"zone\":\"high\"}],\"deadline_ms\":35000}"
}

has_chip() { case "$1" in *"\"id\":\"$CHIP\""*) return 0;; *) return 1;; esac; }

engine_ip() {
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(docker compose ps -q comment-engine)"
}

# gateway를 거치지 않고 엔진 컨테이너 안에서 직접 팩 목록을 물어본다
engine_domains() {
  docker compose exec -T comment-engine bash -c \
    "exec 3<>/dev/tcp/localhost/7001 && printf 'GET /v1/domains HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3 && cat <&3" \
    2>/dev/null || true
}

# 본체(엔진 · gateway)를 재시작하고, 엔진의 칩 목록에 sample 이 있는(present) / 없는(absent) 상태로 뜰 때까지 기다린다
# 엔진은 팩을, gateway는 칩의 경로 · 헬스를 시작할 때 읽는다
restart_body() {
  local want=$1 before d
  before=$(engine_ip)
  docker compose restart comment-engine gateway >/dev/null
  for _ in $(seq 1 60); do
    d=$(engine_domains)
    case "$d" in *'"id":"demo"'*)
      if [ "$want" = present ] && has_chip "$d"; then break; fi
      if [ "$want" = absent ] && ! has_chip "$d"; then break; fi
    esac
    d=
    sleep 2
  done
  [ -n "$d" ] || fail "엔진이 다시 뜨지 않았거나 칩 목록이 기대($want)와 다름"
  echo "본체 재시작 완료. 엔진 IP: $before -> $(engine_ip)"
  docker compose logs --no-color --tail 40 comment-engine | grep -E 'pack loaded|pack\(s\) from' | tail -4 || true
}

# 본체가 다시 뜬 뒤 gateway 경유로도 같은 칩 목록이 보이는지 (gateway가 다시 뜨는 시간까지 기다린다)
wait_gateway() {
  local want=$1
  for _ in $(seq 1 60); do
    call GET /v1/domains
    if [ "$CODE" = 200 ]; then
      if [ "$want" = present ] && has_chip "$BODY"; then show; return 0; fi
      if [ "$want" = absent ] && ! has_chip "$BODY"; then show; return 0; fi
    fi
    sleep 2
  done
  show
  echo "--- gateway 로그"
  docker compose logs --no-color --tail 30 gateway || true
  fail "엔진은 바뀐 칩 목록을 갖고 있는데 gateway 경유로는 보이지 않음 (gateway가 다시 뜨지 않았거나 엔진의 옛 주소를 붙잡고 있을 가능성)"
}

# gateway 관리 포트(4004)에서 하위 서비스 하나의 헬스를 물어 HTTP 코드만 돌려준다 (200 = UP, 503 = DOWN, 404 = 그런 서비스 없음)
health_code() {
  docker compose exec -T gateway bash -c \
    "exec 3<>/dev/tcp/localhost/4004 && printf 'GET /actuator/health/services/$1 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3 && head -1 <&3" \
    2>/dev/null | tr -d '\r' | awk '{print $2}' || true
}

cleanup() {
  if [ -e "chips/$CHIP/compose.yml" ]; then
    plugged rm -sf sample-gen sample-api >/dev/null 2>&1 || true
  fi
  rm -rf "chips/$CHIP"
}

step "0. 본체가 떠 있는지"
call GET /v1/domains
[ "$CODE" = 200 ] || fail "gateway($GW)가 응답하지 않음. 먼저 docker compose up -d --build"
show
if has_chip "$BODY" || [ -e "chips/$CHIP" ]; then fail "$CHIP 칩이 이미 꽂혀 있음"; fi
trap cleanup EXIT

step "1. 꽂기 전: $CHIP 칩의 팩도 경로도 없다"
call POST /v1/comments/light @e2e/chips/$CHIP/light-request.json
show
[ "$CODE" = 404 ] || fail "팩: 404가 아님"
expect UNKNOWN_DOMAIN
call GET /sample/light
show
[ "$CODE" = 404 ] || fail "칩의 경로: 404가 아님"

step "2. 꽂기: 칩 폴더 하나를 chips/ 에 복사 (e2e/chips/$CHIP → chips/$CHIP)"
cp -r "e2e/chips/$CHIP" "chips/$CHIP"
ls "chips/$CHIP"

step "3. 꽂기: 칩의 서비스를 띄우고 본체는 재시작만"
plugged up -d --no-deps sample-gen sample-api >/dev/null
for i in $(seq 1 30); do
  if docker compose exec -T comment-engine bash -c "exec 3<>/dev/tcp/sample-gen/$GEN_PORT" 2>/dev/null \
     && docker compose exec -T comment-engine bash -c "exec 3<>/dev/tcp/sample-api/9310" 2>/dev/null; then break; fi
  [ "$i" -lt 30 ] || fail "칩의 서비스(sample-gen:$GEN_PORT, sample-api:9310)에 닿지 않음"
  sleep 2
done
echo "칩의 서비스 연결 확인: sample-gen:$GEN_PORT, sample-api:9310"
restart_body present
wait_gateway present

step "4-1. 새 칩의 팩: gateway → 엔진 → 칩의 생성 서버"
call POST /v1/comments/light @e2e/chips/$CHIP/light-request.json
show
[ "$CODE" = 200 ] || fail "$CHIP 칩이 200이 아님"
expect "small-gen:$GEN_PORT"
expect '"fallback":false'

step "4-2. 새 칩이 들고 온 경로: gateway /sample/** → 칩의 API 서비스 → 엔진 → 칩의 생성 서버"
call GET /sample/light
show
[ "$CODE" = 200 ] || fail "칩의 경로가 200이 아님"
expect "small-gen:$GEN_PORT"

step "4-3. 새 칩의 서비스가 gateway 헬스에 나온다"
hc=
for _ in $(seq 1 15); do
  hc=$(health_code sample-api)
  [ "$hc" = 200 ] && break
  sleep 2
done
echo "gateway 헬스 services/sample-api: HTTP $hc"
[ "$hc" = 200 ] || fail "gateway 헬스에 sample-api 가 UP으로 나오지 않음"

step "4-4. 기존 칩은 원래대로"
light tetris delta_app 0.05
show
[ "$CODE" = 200 ] || fail "tetris 칩이 200이 아님"
expect "small-gen:9200"
light demo metric_a 0.31
show
[ "$CODE" = 200 ] || fail "demo 칩이 200이 아님"
expect "A가 높습니다"
call GET /api/v1/stats/a
show
[ "$CODE" = 400 ] || fail "tetris 칩의 경로(/api/**)가 원래대로가 아님"
expect INVALID_USERNAME

step "5. 본체 · 슬롯 · 다른 칩은 바뀌지 않았다"
changed=$(git status --porcelain --untracked-files=all | grep -v " chips/$CHIP/" || true)
if [ -n "$changed" ]; then echo "$changed"; fail "chips/$CHIP 밖이 바뀌어 있음"; fi
echo "chips/$CHIP 밖의 변경: 없음"
echo "바뀐 곳:"
git status --porcelain --untracked-files=all -- chips

step "6. 뽑기: 칩의 서비스를 내리고 폴더를 치운 뒤 본체 재시작"
cleanup
restart_body absent
wait_gateway absent
call POST /v1/comments/light @e2e/chips/$CHIP/light-request.json
show
[ "$CODE" = 404 ] || fail "뽑은 뒤에도 팩이 404가 아님"
call GET /sample/light
show
[ "$CODE" = 404 ] || fail "뽑은 뒤에도 칩의 경로가 404가 아님"
[ "$(health_code sample-api)" = 404 ] || fail "뽑은 뒤에도 gateway 헬스에 sample-api 가 남아 있음"
echo "gateway 헬스 services/sample-api: HTTP 404 (없음)"

step "통과: 폴더 하나를 꽂으면 팩 · 서비스 · 경로가 같이 돌고, 뽑으면 같이 사라진다. 본체 수정 없음"
