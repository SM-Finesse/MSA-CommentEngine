# MSA-CommentEngine

수치를 받아 코멘트를 만드는 엔진(본체)과, 분야별 내용(칩)을 꽂는 슬롯입니다. 본체 하나(comment-engine)에 칩 두 개(tetris, demo)가 꽂혀 있습니다.

작업 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 봐 주세요.

> 프로토타입 단계입니다. 화면 경로 중 통계(`/api/v1/stats/{username}`)와 light 코멘트까지 새 구조로 옮겼고, heavy 코멘트는 아직입니다.
> 슬롯의 기준 문서는 「MSA 슬롯 구현 확정안」입니다 (레포에는 아직 없음).

## 무엇이 어디에 있나

```
├── api/                                  ← 슬롯 (약속 3개)
│   └── com/finesse/api/
│       ├── engine/      ① 엔진 계약   CommentEngineService(경로), dto/, EngineErrorCode
│       ├── generation/  ③ 생성 서버 규약   지금 llm-server API 그대로 (동결)
│       └── pack/        ② 팩 규격     DomainPackReader (시작 시 검사 4개)
├── microservices/comment-engine/         ← 본체 (포트 7001)
│   └── com/finesse/engine/
│       ├── EngineController       ① 구현. 검증 → 팩 찾기 → 라우터
│       ├── PackRegistry           시작할 때 팩 전부 읽기. 하나라도 틀리면 안 뜸
│       ├── GenerationRouter       mode = template / server 로 갈라짐
│       ├── TemplateGenerator      phrases로 문장 조립 (template 모드 + 대체 문장)
│       ├── ServerGenerator        라운드로빈 · 30초 제외 · 재시도 · 마감
│       └── GenerationProtocol     ③ 어댑터. metric→stat, high→높음 같은 변환은 여기 한 곳
├── spring-cloud/gateway/                 ← 본체의 입구 (포트 8080). 경로 규칙은 config-repo/gateway.yml
├── config-repo/                          ← 모든 서비스의 설정 (공통 application.yml + gateway · comment-engine · finesse-api.yml)
├── microservices/finesse-api/            ← 칩 쪽 조합 서비스 (포트 8092). 엔진 계약으로 본체를 부른다
│   └── com/finesse/composite/
│       ├── LightCommentDemoController   하이라이트 판정 결과를 받아 엔진을 부르고 화면 모양으로 바꿈
│       ├── EngineClient                 ① 호출하는 쪽 구현. 엔진 에러는 기다리지 않고 바로 화면 코드로
│       ├── EngineHttp                   엔진 HTTP 호출. 다시 시도 · 회로 차단기 (Resilience4j, 수업 13장)
│       └── ApiErrorHandler              엔진 에러 코드 → 화면이 받는 코드
├── microservices/tetris-analytics/       ← tetris 칩의 분석 서비스 (포트 7002). 모놀리식 calc·통계를 고치지 않고 옮김
├── domain-packs/                         ← 칩 (엔진 이미지 밖)
│   ├── tetris/   server 모드, 지표 11개 (문구는 모놀리식 대체 문장 틀에서 옮김. 손질은 C단계, 팀)
│   └── demo/     template 모드
├── small-gen/SmallGenServer.java         ← 작은 생성 서버 (③ 3개 경로만). tetris 칩의 llm-server 대신
└── try.ps1                               ← 엔진 계약으로 불러 보는 스크립트
```

## 실행

PowerShell 창 3개를 레포 루트에서 씁니다. Java 25는 Gradle이 알아서 찾습니다.

```powershell
# 창 1 — 작은 생성 서버 (tetris 칩의 llm-server 대신)
java small-gen/SmallGenServer.java 9200
```

```powershell
# 창 2 — 본체. tetris 팩의 서버 주소를 환경변수로 small-gen으로 돌림
$env:FINESSE_PACK_TETRIS_SERVERS = "http://localhost:9200"
.\gradlew :microservices:comment-engine:bootRun
```

```powershell
# 창 3 — 불러 보기
.\try.ps1
```

시작 로그에 `pack loaded: demo ...`, `pack loaded: tetris ...`가 보이면 슬롯에 칩 두 개가 꽂힌 것입니다.

설정은 서비스마다 `config-repo/<서비스 이름>.yml`에 있습니다. 위처럼 `gradlew`로 띄우면 모듈 폴더에서 `../../config-repo`를 읽습니다. IntelliJ의 실행 버튼으로 띄울 때 `config-repo`를 못 찾는다고 나오면, 실행 설정의 작업 폴더를 모듈 폴더로 바꾸거나 환경변수 `CONFIG_DIR`에 `config-repo` 폴더 경로를 넣어 주세요.

칩 쪽 서비스까지 거쳐 보려면 창을 하나 더 열어 finesse-api를 띄웁니다.

```powershell
.\gradlew :microservices:finesse-api:bootRun
```

```powershell
curl.exe -s -H "Content-Type: application/json" http://localhost:8092/api/v1/comment-demo/light -d '{\"domain\":\"demo\",\"highlights\":[{\"stat\":\"metric_a\",\"value\":0.31,\"zone\":\"high\"}]}'
```

## docker compose로 한 번에 띄우기

gateway, comment-engine, finesse-api, tetris-analytics, small-gen, zipkin을 같이 띄웁니다. 밖으로 여는 포트는 gateway의 8080과 추적 화면(Zipkin)의 9411입니다.

| gateway 경로 | 넘어가는 곳 |
|---|---|
| `/v1/comments/**`, `/v1/domains` | comment-engine (본체) |
| `/api/**` | finesse-api (칩 쪽 API, 화면이 부르는 곳) → comment-engine, tetris-analytics |

| 화면이 부르는 경로 | 처리 |
|---|---|
| `GET /api/v1/stats/{username}` | finesse-api가 tetris-analytics에 그대로 넘기고 응답도 그대로 돌려줌. 모놀리식과 같은 경로·응답 |
| `GET /api/v1/comment/{username}?scope=light` | finesse-api: stats(tetris-analytics) → calc가 고른 하이라이트 → 엔진 light(tetris 칩) → 모놀리식과 같은 모양 `{light_summary, highlights[{stat, sentence}]}`. 캐시 10분 |
| `GET /api/v1/comment/{username}?scope=heavy` | 아직 없음 (E단계, 501) |

```bash
./gradlew build -x test
```

```bash
docker compose up -d --build
```

```bash
curl http://localhost:8080/v1/domains
```

```bash
curl http://localhost:8080/api/v1/comment-demo/domains
```

```bash
curl http://localhost:8080/api/v1/stats/<TETR.IO 이름>
```

통계는 실제 TETR.IO를 부릅니다 (처음 조회는 수집에 10초 안팎, 이후 10분은 캐시).

추적 화면은 http://localhost:9411 입니다. gateway → finesse-api → comment-engine 요청이 한 줄로 이어져 보입니다.

```bash
docker compose down
```

칩(`domain-packs`)과 설정(`config-repo`)은 이미지에 굽지 않고 폴더째 꽂습니다. 팩이나 설정을 바꾸면 이미지를 다시 만들지 않고 `docker compose restart`만 하면 됩니다.

## 새 칩 꽂아 보기 (본체 5단계 확인)

본체 코드를 한 줄도 고치지 않고 칩을 꽂고 뽑을 수 있는지 확인합니다. `e2e/packs/sample/`은 이 확인에만 쓰는 시험용 칩이고, 실제 칩이 아닙니다.

docker compose로 본체를 띄운 상태에서 레포 루트에서 실행합니다.

```bash
bash e2e/plug-test.sh
```

| 순서 | 하는 일 | 확인하는 것 |
|---|---|---|
| 1 | 꽂기 전에 sample 칩을 부름 | 404 `UNKNOWN_DOMAIN` |
| 2 | sample 칩 전용 생성 서버(small-gen, 9300)를 하나 더 띄움 | 칩마다 서버 하나 (C안) |
| 3 | `e2e/packs/sample`을 `domain-packs/`에 복사하고 엔진만 재시작 | 이미지 재빌드 없음 |
| 4 | gateway 경유로 sample, tetris, demo를 부름 | sample은 9300의 문장, tetris는 9200의 문장, demo는 phrases 문장 |
| 5 | `git status`로 본체·슬롯 코드 확인 | 바뀐 곳은 `domain-packs/sample/`뿐 |
| 6 | 칩과 생성 서버를 치우고 엔진 재시작 | 다시 404 |

PR마다 GitHub에서 같은 확인이 자동으로 돕니다. Windows에서는 돌려보지 않았습니다.

손으로 해 볼 때 (PowerShell, 확인 안 함). 꽂기:

```powershell
docker compose run -d --name sample-gen small-gen java /app/SmallGenServer.java 9300
```

```powershell
Copy-Item -Recurse e2e\packs\sample domain-packs\sample
```

```powershell
docker compose restart comment-engine
```

```powershell
curl.exe -s -H "Content-Type: application/json" --data-binary "@e2e/sample-light.json" http://localhost:8080/v1/comments/light
```

뽑기:

```powershell
docker rm -f sample-gen
```

```powershell
Remove-Item -Recurse domain-packs\sample
```

```powershell
docker compose restart comment-engine
```

## 느껴 볼 것 6가지

| # | 해 볼 것 | 보이는 것 | 확정안 |
|---|---|---|---|
| 1 | 그냥 `try.ps1` | demo는 `phrases` 문장, tetris는 `[small-gen:9200] …` 문장. **같은 엔진, 같은 경로** | 2장 |
| 2 | 창 2에서 환경변수를 빼고 재시작 (tetris가 `localhost:9101` · `9102`를 봄) | tetris light는 `phrases` 대체 문장, `fallback: true`로 200. tetris section은 `503 LLM_UNAVAILABLE` + `Retry-After: 30` | 3.4절 |
| 3 | small-gen을 `$env:FAIL_RATE=100`으로 띄움 | tetris 문장이 `phrases` 대체 문장으로 바뀌고 `fallback: true` | 7절 |
| 4 | small-gen을 `$env:FAIL_RATE=50`으로 띄움 | 엔진 로그에서 실패 → 재시도 → 성공이 보임. 일부 항목만 `fallback: true`일 때도 있음 | 7.2절 |
| 5 | `domain-packs/demo/metrics.json`에서 `zero` 한 칸을 `""`로 바꾸고 재시작 | 엔진이 `metric_b.phrases.zero is blank`로 **아예 안 뜸** | 4.5절 |
| 6 | `domain-packs/demo/pack.json`의 `generation`을 `{ "mode": "server", "servers": ["http://localhost:9200"] }`로 바꾸고 재시작 | demo 문장이 small-gen에서 나옴. **엔진 코드 수정 0줄** | 9절 4단계 |

> 5 · 6번은 다 본 뒤에 파일을 원래대로 돌려 두세요.

마감 초과(`504 LLM_TIMEOUT`)를 보려면 small-gen을 `$env:DELAY_MS=4000`으로 띄우고, 요청의 `deadline_ms`를 `1000`으로 줄입니다.

## 시험

```powershell
.\gradlew test
```

| 모듈 | 시험 | 보는 것 |
|---|---|---|
| `api` | `Stage1Test` (7개) | DTO ↔ JSON 왕복과 snake_case, 입력 검증, 팩 리더(실제 팩 통과 · 고장 팩 5개 · 환경변수 덮어쓰기), 본체 코드에 `delta_` · `tetr` · `chapter` · `챕터` 없음 |
| `comment-engine` | `CommentEngineTest` (6개) | demo(template)와 tetris(server, 가짜 생성 서버)를 엔진 계약으로 호출, 팩 목록, 에러 코드 |
| `comment-engine` | `GenServerDownTest` (5개) | 생성 서버가 전부 꺼지면 light는 대체 문장 200, section은 503, 다른 칩은 정상. tetris 칩이 하이라이트 후보 11개를 모두 받는지, 영역과 부호가 반대면 weak 문장 |
| `comment-engine` | `TemplateGeneratorTest` (3개) | 대체 문장 7가지 상태(영역 × 부호, 0), weak 칸이 없는 팩은 normal 칸, 반올림한 값의 부호로 고름 |
| `comment-engine` | `PackRegistryTest` (1개) | 잘못된 팩이 있으면 시작 실패 |
| `finesse-api` | `FinesseApiTest` (3개) | 가짜 엔진을 세우고 계약대로 부르는지, 응답 모양과 에러 코드 변환 |
| `finesse-api` | `EngineResilienceTest` (4개) | 엔진 503은 바로 넘김(다시 안 부름), 엔진에 닿지 않으면 한 번만 다시, 회로 열림(엔진을 안 부름)과 닫힘 |
| `finesse-api` | `LightCommentFlowTest` (9개) | 콜드스타트 문장, 엔진 계약으로 부르기(값은 화면의 원값), 캐시(같은 stats면 꺼냄, stats가 새로 계산되면 다시), 대체 문장·3개 미만은 캐시 안 함, 지표 고르기 규칙, 고른 지표가 없으면 엔진 안 부름, stats 에러 그대로, scope 검사 |
| `finesse-api` | `StatsRelayTest` (7개) | 통계를 바이트까지 그대로 넘김, 경로·쿼리를 받은 그대로, 에러 응답(404·503 + Retry-After)도 그대로, 닿지 않으면 한 번만 다시, 계속 안 되면 502 `TETRIO_API_UNAVAILABLE`, 답이 늦으면 다시 안 부름, 회로 열림 |
| `gateway` | `GatewayRoutingTests` (5개) | 엔진 경로 3개와 칩 API 경로(`/api/**`)가 넘어가는지, 규칙 밖 경로는 404 |
| `gateway` | `GatewayHealthTests` (1개) | gateway 헬스에 하위 서비스 상태가 나오는지 (UP, 회로 열림 → DOWN, 닿지 않음 → DOWN) |
| `tetris-analytics` | 모놀리식에서 옮긴 시험 41개 | `calc` 35개(ArchUnit 경계 시험 포함), 통계 서비스, TETR.IO 클라이언트, 사용자명, 앱 기동. 실제 TETR.IO를 부르는 2개는 기본으로 건너뜀 |

PR을 올리면 GitHub에서 같은 시험이 자동으로 돕니다 (`build-test`의 `test` 작업). `compose` 작업은 docker compose로 본체를 띄운 뒤 gateway 경유 호출(엔진 직접, finesse-api 경유), 추적, 칩 꽂기·뽑기(`e2e/plug-test.sh`), 엔진 IP가 바뀌었을 때(`e2e/engine-ip-change.sh`), 회로 차단기 열림·닫힘(`e2e/circuit-breaker.sh`), gateway 헬스 모음(`e2e/gateway-health.sh`), 통계 넘기기(`e2e/stats-relay.sh`)를 확인합니다.

모놀리식과 같은 응답인지는 `compare-monolith` 워크플로(수동 실행)로 봅니다. 실제 TETR.IO를 한 번만 부르고(러너의 기록·재생 프록시 `e2e/tetrio-replay.py`), 같은 데이터로 모놀리식 backend와 MSA(gateway 경유)의 `/api/v1/stats/{username}`를 불러 `updated_at`만 빼고 비교합니다 (`e2e/compare-stats.py`). light(`?scope=light`)는 CI에 LLM 서버가 없어 양쪽 다 대체 문장이 나오므로, 하이라이트 지표·순서와 지표별 문장(앞의 비교 표현을 뗀 것)을 비교합니다. Actions 탭 → compare-monolith → Run workflow에서 유저명을 바꿔 돌릴 수 있습니다.

## 만들다가 발견한 것 (확정안에 반영 필요)

| # | 내용 | 지금은 |
|---|---|---|
| 1 | 지금 llm-server는 zone을 **`높음` · `보통` · `낮음`**으로 받는데, 엔진 계약은 `high` · `normal` · `low`. 확정안 5절 변환 표에 zone이 빠져 있음 | `GenerationProtocol`에서 변환 |
| 2 | 생성 서버 규약을 동결하면 `chapter_id` · `/heavy-chapter`가 본체 코드에 남음 → 9.1절 시험 4("chapter 0건")와 충돌 | 변환을 `GenerationProtocol` 한 곳에 모으고, 그 파일과 `api/generation/`만 시험에서 제외 |
| 3 | 생성 서버가 전부 실패할 때 503으로 끝낼지, `phrases` 대체 문장으로 200을 줄지가 7절 그림만으로는 갈림 | 결정(11/15): 지금 서비스와 같게. light는 어떤 실패든 대체 문장 200 (모놀리식 light-v5.2 "절대 예외를 던지지 않고 문장 틀로 채움"). section은 전부 연결 실패 → 503, 시간 초과 → 504, 형식 오류 → 502, 그 밖 → 대체 문장 200 (모놀리식 heavy 챕터는 failed로 끝남). 확정안 7절 반영 필요 |
| 4 | 엔진은 503에 `Retry-After: 30`을 붙이는데, finesse-api가 그 시간만큼 기다렸다가 한 번 더 불러 light 마감 40초 중 30초를 기다렸음 | 해결: finesse-api는 엔진이 에러로 답하면 기다리지 않고 바로 화면에 넘김(Retry-After 30도 그대로). 다시 시도는 엔진에 닿지 않을 때만 1초 뒤 한 번 (Resilience4j) |
| 5 | 엔진이 영역(high·low)만 보고 대체 문장을 골라, 영역은 high인데 값이 음수면 "뚜렷하게 앞섭니다(-0.03)"처럼 앞뒤가 맞지 않았음. 모놀리식은 10/26 A안대로 방향은 값의 부호, 강도는 영역 (7가지) | **슬롯 규격 변경(11/15, 박덕현 결정)**: 팩 `phrases`에 `weak_pos` · `weak_neg`를 선택 칸으로 추가. 영역과 부호가 반대면 weak 칸, 없으면 같은 부호의 normal 칸. 예전 팩은 그대로 통과. 확정안 4.3절(metrics.json)과 7.1절(문장 고르기) 반영 필요 |
