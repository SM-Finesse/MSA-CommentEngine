#!/usr/bin/env bash
# gateway 헬스 하나로 본체 전체 상태를 보는지 (HealthCheckConfiguration)
#
#   1. 모두 떠 있으면 gateway 헬스의 services 에 comment-engine · finesse-api 가 UP
#   2. 엔진을 멈추면 comment-engine 이 DOWN, gateway 헬스도 DOWN
#   3. 엔진을 다시 띄우면 다시 UP
#
# 상태는 JSON 안의 순서에 기대지 않고 HTTP 코드로 본다: 헬스가 UP이면 200, DOWN이면 503.
# 하위 서비스 하나의 상태는 /actuator/health/services/<이름> 으로 따로 물어본다.
# 관리 포트(4004)는 밖으로 열려 있지 않아 gateway 컨테이너 안에서 부른다.
#
# 본체가 docker compose로 떠 있는 상태에서 실행한다:  bash e2e/gateway-health.sh
# 확인한 환경: GitHub Actions (ubuntu). Windows Git Bash에서는 돌려보지 않았다.

set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

fail() { printf '\n실패: %s\n' "$*" >&2; exit 1; }

# gateway 관리 포트의 경로를 불러 HTTP 코드만 돌려준다
code_for() {
  docker compose exec -T gateway bash -c \
    "exec 3<>/dev/tcp/localhost/4004 && printf 'GET $1 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3 && head -1 <&3" \
    2>/dev/null | tr -d '\r' | awk '{print $2}' || true
}

# 하위 서비스 하나의 상태 (UP · DOWN)
state_of() {
  case "$(code_for "/actuator/health/services/$1")" in
    200) echo UP ;;
    503) echo DOWN ;;
    *) echo "?" ;;
  esac
}

show() {
  echo "gateway 헬스: HTTP $(code_for /actuator/health)  comment-engine=$(state_of comment-engine)  finesse-api=$(state_of finesse-api)"
}

# 원하는 상태가 될 때까지 기다린다 (최대 40초)
wait_for() {
  local name=$1 want=$2
  for _ in $(seq 1 20); do
    if [ "$(state_of "$name")" = "$want" ]; then show; return 0; fi
    sleep 2
  done
  show
  fail "$name 이(가) $want 가 되지 않음"
}

restore_engine() { docker compose up -d --no-deps --wait --wait-timeout 120 comment-engine >/dev/null 2>&1 || true; }

echo "== 1. 모두 떠 있을 때"
wait_for comment-engine UP
wait_for finesse-api UP
[ "$(code_for /actuator/health)" = 200 ] || fail "gateway 헬스가 UP(200)이 아님"

echo "== 2. 엔진을 멈춘다"
docker compose stop comment-engine >/dev/null
trap restore_engine EXIT
wait_for comment-engine DOWN
[ "$(code_for /actuator/health)" = 503 ] || fail "gateway 헬스가 DOWN(503)이 아님"
[ "$(state_of finesse-api)" = UP ] || fail "finesse-api 는 그대로 UP 이어야 함"

echo "== 3. 엔진을 다시 띄운다"
docker compose up -d --no-deps --wait --wait-timeout 120 comment-engine >/dev/null
wait_for comment-engine UP
[ "$(code_for /actuator/health)" = 200 ] || fail "gateway 헬스가 다시 UP(200)이 되지 않음"

echo "== 통과: gateway 헬스 하나로 하위 서비스 상태를 본다"
