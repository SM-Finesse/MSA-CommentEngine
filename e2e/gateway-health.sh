#!/usr/bin/env bash
# gateway 헬스 하나로 본체 전체 상태를 보는지 (HealthCheckConfiguration)
#
#   1. 모두 떠 있으면 gateway 헬스의 services 에 comment-engine · finesse-api 가 UP
#   2. 엔진을 멈추면 comment-engine 이 DOWN, gateway 헬스도 DOWN
#   3. 엔진을 다시 띄우면 다시 UP
#
# 관리 포트(4004)는 밖으로 열려 있지 않아 gateway 컨테이너 안에서 부른다.
# 본체가 docker compose로 떠 있는 상태에서 실행한다:  bash e2e/gateway-health.sh
# 확인한 환경: GitHub Actions (ubuntu). Windows Git Bash에서는 돌려보지 않았다.

set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

fail() { printf '\n실패: %s\n' "$*" >&2; exit 1; }

health() {
  docker compose exec -T gateway bash -c \
    "exec 3<>/dev/tcp/localhost/4004 && printf 'GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3 && cat <&3" \
    2>/dev/null | tr -d '\r' | grep -o '{.*}' | head -1 || true
}

# 이름의 상태 (예: status_of "$h" comment-engine → UP)
status_of() { printf '%s' "$1" | grep -o "\"$2\":{\"status\":\"[A-Z_]*\"" | cut -d'"' -f6; }

show() {
  local h=$1
  echo "gateway 헬스: $(printf '%s' "$h" | cut -c1-15)…  comment-engine=$(status_of "$h" comment-engine)  finesse-api=$(status_of "$h" finesse-api)"
}

# 원하는 상태가 될 때까지 기다린다 (최대 40초)
wait_for() {
  local name=$1 want=$2 h
  for _ in $(seq 1 20); do
    h=$(health)
    if [ "$(status_of "$h" "$name")" = "$want" ]; then show "$h"; return 0; fi
    sleep 2
  done
  show "$h"
  fail "$name 이(가) $want 가 되지 않음"
}

restore_engine() { docker compose up -d --no-deps --wait --wait-timeout 120 comment-engine >/dev/null 2>&1 || true; }

echo "== 1. 모두 떠 있을 때"
wait_for comment-engine UP
wait_for finesse-api UP
h=$(health)
case "$h" in '{"status":"UP"'*) ;; *) fail "gateway 헬스가 UP이 아님";; esac

echo "== 2. 엔진을 멈춘다"
docker compose stop comment-engine >/dev/null
trap restore_engine EXIT
wait_for comment-engine DOWN
h=$(health)
case "$h" in '{"status":"DOWN"'*) ;; *) fail "gateway 헬스가 DOWN이 아님";; esac

echo "== 3. 엔진을 다시 띄운다"
docker compose up -d --no-deps --wait --wait-timeout 120 comment-engine >/dev/null
wait_for comment-engine UP

echo "== 통과: gateway 헬스 하나로 하위 서비스 상태를 본다"
