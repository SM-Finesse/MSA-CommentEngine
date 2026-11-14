#!/usr/bin/env bash
# 칩 꽂기 시험 (본체 설계 5단계): 본체 코드를 한 줄도 고치지 않고 칩을 꽂고 뽑을 수 있는가
#
# 본체(gateway, comment-engine)가 docker compose로 떠 있는 상태에서 실행한다.
#   bash e2e/plug-test.sh
#
#   1. 꽂기 전: 새 칩(sample)을 gateway 경유로 부르면 404 UNKNOWN_DOMAIN
#   2. 꽂기: 새 칩 전용 생성 서버를 띄운다 (C안: 칩마다 서버 하나)
#   3. 꽂기: 칩 폴더를 domain-packs에 복사하고 엔진만 재시작 (이미지 재빌드 없음)
#   4. gateway 경유로 새 칩은 자기 생성 서버(9300)의 문장을, 기존 칩(tetris, demo)은 원래 문장을 돌려준다
#   5. 본체·슬롯 코드는 바뀌지 않았다 (git status)
#   6. 뽑기: 칩과 생성 서버를 치우고 엔진을 재시작하면 다시 404
#
# e2e/packs/sample 은 이 시험에만 쓰는 칩이다. 실제 칩이 아니다.
# 확인한 환경: GitHub Actions (ubuntu). Windows Git Bash에서는 돌려보지 않았다.

set -euo pipefail
export MSYS_NO_PATHCONV=1   # Windows Git Bash가 /app 같은 인자를 C:/... 로 바꾸지 않게
cd "$(dirname "$0")/.."

GW=http://localhost:8080
CHIP=sample
GEN=sample-gen
GEN_PORT=9300
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

# 엔진을 재시작하고, 칩 목록에 sample 이 있는(present) / 없는(absent) 상태로 뜰 때까지 기다린다
restart_engine() {
  local want=$1 before d
  before=$(engine_ip)
  docker compose restart comment-engine >/dev/null
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
  echo "엔진 재시작 완료. 엔진 IP: $before -> $(engine_ip)"
  docker compose logs --no-color --tail 40 comment-engine | grep -E 'pack loaded|pack\(s\) from' | tail -4 || true
}

# 엔진이 바뀐 뒤 gateway 경유로도 같은 칩 목록이 보이는지
wait_gateway() {
  local want=$1
  for _ in $(seq 1 15); do
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
  fail "엔진은 바뀐 칩 목록을 갖고 있는데 gateway 경유로는 보이지 않음 (gateway가 엔진의 옛 주소를 붙잡고 있을 가능성)"
}

cleanup() {
  docker rm -f "$GEN" >/dev/null 2>&1 || true
  rm -rf "domain-packs/$CHIP"
}

step "0. 본체가 떠 있는지"
call GET /v1/domains
[ "$CODE" = 200 ] || fail "gateway($GW)가 응답하지 않음. 먼저 docker compose up -d --build"
show
if has_chip "$BODY" || [ -e "domain-packs/$CHIP" ]; then fail "$CHIP 칩이 이미 꽂혀 있음"; fi
trap cleanup EXIT

step "1. 꽂기 전: $CHIP 칩을 부르면 404"
call POST /v1/comments/light @e2e/sample-light.json
show
[ "$CODE" = 404 ] || fail "404가 아님"
expect UNKNOWN_DOMAIN

step "2. 꽂기: $CHIP 칩 전용 생성 서버를 띄운다 ($GEN:$GEN_PORT)"
docker compose run -d --name "$GEN" small-gen java /app/SmallGenServer.java "$GEN_PORT" >/dev/null
for i in $(seq 1 30); do
  if docker compose exec -T comment-engine bash -c "exec 3<>/dev/tcp/$GEN/$GEN_PORT" 2>/dev/null; then break; fi
  [ "$i" -lt 30 ] || fail "엔진 컨테이너에서 $GEN:$GEN_PORT 에 닿지 않음"
  sleep 2
done
echo "엔진 → $GEN:$GEN_PORT 연결 확인"

step "3. 꽂기: e2e/packs/$CHIP 을 domain-packs/ 에 복사하고 엔진만 재시작"
cp -r "e2e/packs/$CHIP" "domain-packs/$CHIP"
restart_engine present
wait_gateway present

step "4. gateway 경유로 세 칩을 부른다"
call POST /v1/comments/light @e2e/sample-light.json
show
[ "$CODE" = 200 ] || fail "$CHIP 칩이 200이 아님"
expect "small-gen:$GEN_PORT"
expect '"fallback":false'
light tetris delta_app 0.05
show
[ "$CODE" = 200 ] || fail "tetris 칩이 200이 아님"
expect "small-gen:9200"
light demo metric_a 0.31
show
[ "$CODE" = 200 ] || fail "demo 칩이 200이 아님"
expect "A가 높습니다"

step "5. 본체·슬롯 코드는 바뀌지 않았다"
changed=$(git status --porcelain --untracked-files=all -- \
  api microservices spring-cloud config-repo small-gen docker-compose.yml settings.gradle build.gradle gradle)
if [ -n "$changed" ]; then echo "$changed"; fail "본체·슬롯 코드가 바뀌어 있음"; fi
echo "본체·슬롯 코드 변경: 없음"
echo "바뀐 곳:"
git status --porcelain --untracked-files=all -- domain-packs

step "6. 뽑기: 칩과 생성 서버를 치우고 엔진 재시작"
cleanup
restart_engine absent
wait_gateway absent
call POST /v1/comments/light @e2e/sample-light.json
show
[ "$CODE" = 404 ] || fail "뽑은 뒤에도 404가 아님"

step "통과: 꽂으면 돌고, 뽑으면 사라진다. 본체 코드 수정 없음"
