#!/usr/bin/env bash
# 엔진 IP가 바뀌어도 엔진을 부르는 쪽(gateway, finesse-api)이 엔진을 찾는지
#
# 엔진 컨테이너를 다시 만들 때(새 이미지 배포, 재시작) 그 사이 다른 컨테이너가 엔진의 옛 IP를 차지하면
# 엔진은 새 IP를 받는다. 부르는 쪽이 comment-engine 이라는 이름의 옛 주소를 기억하고 있으면 요청이 엉뚱한 곳으로 간다.
#
#   1. 두 경로로 한 번씩 불러 부르는 쪽이 지금 엔진 주소를 쓰게 한다
#        gateway → 엔진               (GET /v1/domains)
#        gateway → finesse-api → 엔진 (GET /api/v1/comment-demo/domains)
#   2. 엔진을 멈추고, 빈 컨테이너(ip-holder)를 띄워 엔진의 옛 IP를 차지하게 한다
#   3. 엔진을 새로 만든다 → 새 IP
#   4. 두 경로로 다시 부른다. 30초 안에 둘 다 200이 나와야 한다
#
# 본체가 docker compose로 떠 있는 상태에서 실행한다:  bash e2e/engine-ip-change.sh
# 확인한 환경: GitHub Actions (ubuntu). Windows Git Bash에서는 돌려보지 않았다.

set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."

GW=http://localhost:8080
HOLDER=ip-holder
ENGINE_PATH=/v1/domains
API_PATH=/api/v1/comment-demo/domains

eng() { docker compose ps -q comment-engine; }
ip_of() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$1"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$GW$1" || true; }
cleanup() { docker rm -f "$HOLDER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

net=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(eng)")
image=$(docker inspect -f '{{.Config.Image}}' "$(eng)")
old=$(ip_of "$(eng)")

echo "== 1. 두 경로로 한 번씩 부른다 (엔진 IP $old)"
for p in "$ENGINE_PATH" "$API_PATH"; do
  c=$(code "$p")
  echo "$p → HTTP $c"
  if [ "$c" != 200 ]; then echo "실패: $p 가 200이 아님"; exit 1; fi
done

echo "== 2. 엔진을 멈추고 옛 IP를 다른 컨테이너가 차지하게 한다"
docker compose stop comment-engine >/dev/null
docker run -d --name "$HOLDER" --network "$net" --entrypoint sleep "$image" 600 >/dev/null
echo "ip-holder IP: $(ip_of "$HOLDER")"

echo "== 3. 엔진을 새로 만든다"
docker compose up -d --no-deps --force-recreate --wait --wait-timeout 120 comment-engine
new=$(ip_of "$(eng)")
echo "엔진 IP: $old -> $new"
if [ "$new" = "$old" ]; then
  echo "실패: 엔진 IP가 바뀌지 않아 이 시험이 성립하지 않음"
  exit 1
fi

echo "== 4. 두 경로로 다시 부른다"
ok_engine=
ok_api=
for i in $(seq 1 15); do
  if [ -z "$ok_engine" ]; then
    c=$(code "$ENGINE_PATH")
    echo "try $i: gateway → 엔진               HTTP $c"
    if [ "$c" = 200 ]; then ok_engine=$i; fi
  fi
  if [ -z "$ok_api" ]; then
    c=$(code "$API_PATH")
    echo "try $i: gateway → finesse-api → 엔진 HTTP $c"
    if [ "$c" = 200 ]; then ok_api=$i; fi
  fi
  if [ -n "$ok_engine" ] && [ -n "$ok_api" ]; then
    echo "통과: 엔진 IP가 바뀌어도 gateway는 ${ok_engine}번째, finesse-api는 ${ok_api}번째 호출에서 새 주소로 부른다"
    exit 0
  fi
  sleep 2
done
echo "--- gateway 로그"
docker compose logs --no-color --tail 20 gateway || true
echo "--- finesse-api 로그"
docker compose logs --no-color --tail 20 finesse-api || true
echo "실패: 엔진은 새 IP($new)로 떴는데 엔진을 찾지 못한 경로가 있음 (옛 주소 $old 를 기억하고 있을 가능성)"
exit 1
