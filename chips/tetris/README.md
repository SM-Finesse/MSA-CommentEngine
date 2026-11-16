# tetris 칩

TETR.IO 전적을 분석해 코멘트를 만드는 칩입니다. 테트리스에만 해당하는 것은 전부 이 폴더에 있습니다.

| 위치 | 내용 |
|---|---|
| `pack/` | 팩. server 모드, 하이라이트 후보 지표 11개 × 문구 7칸, 섹션 8개 |
| `tetris-analytics/` | 분석 서비스 (7002). 모놀리식 `calc`·통계를 고치지 않고 옮김. [README](tetris-analytics/README.md) |
| `finesse-api/` | 화면이 부르는 API (8092). 통계는 tetris-analytics에 넘기고, 코멘트(light, heavy SSE)는 엔진 계약으로 본체를 부름 |
| `config/` | 두 서비스의 설정 |
| `gateway.yml` | gateway에 꽂는 것: `/api/**` → finesse-api, 헬스 2개 |
| `compose.yml` | 화면 + 두 서비스 + 생성 서버 자리(small-gen) + 엔진에 알려 줄 생성 서버 주소 |

```
화면(frontend :8088) ─▶ gateway /api/** ─▶ finesse-api ─┬─▶ comment-engine (본체) ─▶ 이 칩의 생성 서버
                                                        └─▶ tetris-analytics ─▶ TETR.IO
```

## 화면

운영 중인 Finesse 프론트 이미지(`pdh20020317/finesse-frontend`)를 코드 변경 없이 그대로 씁니다. 그 이미지의 Nginx는 `/api/**`를 `BACKEND_UPSTREAM`으로 넘기는데, 모놀리식 backend 대신 본체의 gateway를 가리키게 했습니다. 화면이 부르는 경로 셋(통계, light, heavy)과 응답 모양이 모놀리식과 같아서 화면 쪽은 바꿀 것이 없습니다.

띄운 뒤 http://localhost:8088 을 엽니다.

MSA에서만 더한 것 두 가지는 이미지가 아니라 `frontend/` 폴더의 Nginx 설정으로 붙입니다 (이미지 코드는 그대로).

| 파일 | 하는 일 |
|---|---|
| `frontend/default.conf.template` | 이미지의 Nginx 설정 + `/engine/`을 서비스 선택 화면(select-screen)으로 넘김 + 화면에 `engine-logo.js`를 끼워 넣음 |
| `frontend/engine-logo.js` | 푸터의 v0.1 오른쪽에 CommentEngine 로고. 누르면 `/engine/`(서비스 선택 화면)으로 감 |

그래서 칩 화면 주소(예: msa.finesse-lab.com) 아래 `/engine/`에서 선택 화면이 열리고, 따로 도메인을 정하지 않아도 로고가 동작합니다. 이미지의 Nginx 설정이 바뀌면 `default.conf.template`도 맞춰 줍니다.

## 생성 서버

기본은 시연용 `small-gen`(9200)입니다. 실제 llm-server(11·13번 PC)로 바꾸려면 레포 루트 `.env`에서 `TETRIS_GEN_SERVERS` 줄의 주석을 풉니다. llm-server 코드는 바꾸지 않습니다.

## 12번에서 실제 llm-server로 돌려 보기

운영 모놀리식(80 포트)은 건드리지 않고 옆에 띄웁니다. 쓰는 포트는 8080(gateway), 8088(화면), 8090(서비스 선택 화면), 9411(추적)입니다. 레포 루트에서 순서대로 실행합니다.

```bash
./gradlew build -x test
```

`.env`에서 `TETRIS_GEN_SERVERS` 줄의 주석을 풉니다 (11·13번 주소).

```bash
docker compose up -d --build
```

```bash
docker compose ps
```

```bash
bash e2e/live-check.sh 5han
```

브라우저에서 http://localhost:8088 을 열어 라이트 뷰와 헤비 뷰를 봅니다.

운영 사이트(finesse-lab.com)를 새 구조로 바꾸는 것은 Cloudflare Tunnel이 가리키는 곳을 운영 화면(80)에서 이 화면(8088)으로 바꾸는 일입니다. 위 확인이 끝난 뒤에 합니다. 되돌릴 때는 Tunnel을 다시 80으로 돌립니다.

알아 둘 것

- 화면의 Nginx는 시작할 때 gateway의 주소를 한 번 찾아 기억합니다. gateway 컨테이너를 새로 만들었으면(`up --force-recreate` 등) `docker compose restart frontend`도 같이 합니다
- 생성 서버가 1대뿐이면 `config/finesse-api.yml`의 `app.endpoint.heavy-seconds`를 120으로 (모놀리식의 1대 기준)

## 시험

```bash
./gradlew :chips:tetris:finesse-api:test
```

```bash
./gradlew :chips:tetris:tetris-analytics:test
```
