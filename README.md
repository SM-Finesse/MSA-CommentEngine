# MSA-CommentEngine

수치를 받아 코멘트를 만드는 엔진(본체)과, 분야별 내용(칩)을 꽂는 슬롯입니다. 본체 하나(comment-engine)에 칩 두 개(tetris, demo)가 꽂혀 있습니다.

작업 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 봐 주세요.

> 프로토타입 단계입니다. tetris-analytics는 아직 없습니다.
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
├── config-repo/                          ← 설정 (공통 application.yml + 서비스별 yml)
├── microservices/finesse-api/            ← 칩 쪽 조합 서비스 (포트 8092). 엔진 계약으로 본체를 부른다
│   └── com/finesse/composite/
│       ├── LightCommentDemoController   하이라이트 판정 결과를 받아 엔진을 부르고 화면 모양으로 바꿈
│       ├── EngineClient                 ① 호출하는 쪽 구현. 503일 때만 한 번 더
│       └── ApiErrorHandler              엔진 에러 코드 → 화면이 받는 코드
├── domain-packs/                         ← 칩 (엔진 이미지 밖)
│   ├── tetris/   server 모드, 지표 3개만 (실제는 11개)
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

칩 쪽 서비스까지 거쳐 보려면 창을 하나 더 열어 finesse-api를 띄웁니다.

```powershell
.\gradlew :microservices:finesse-api:bootRun
```

```powershell
curl.exe -s -H "Content-Type: application/json" http://localhost:8092/api/v1/comment-demo/light -d '{\"domain\":\"demo\",\"highlights\":[{\"stat\":\"metric_a\",\"value\":0.31,\"zone\":\"high\"}]}'
```

## docker compose로 한 번에 띄우기

gateway, comment-engine, small-gen을 같이 띄웁니다. 밖으로 여는 포트는 gateway의 8080 하나입니다.

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
docker compose down
```

칩(`domain-packs`)과 설정(`config-repo`)은 이미지에 굽지 않고 폴더째 꽂습니다. 팩이나 설정을 바꾸면 이미지를 다시 만들지 않고 `docker compose restart`만 하면 됩니다.

## 느껴 볼 것 6가지

| # | 해 볼 것 | 보이는 것 | 확정안 |
|---|---|---|---|
| 1 | 그냥 `try.ps1` | demo는 `phrases` 문장, tetris는 `[small-gen:9200] …` 문장. **같은 엔진, 같은 경로** | 2장 |
| 2 | 창 2에서 환경변수를 빼고 재시작 (tetris가 `localhost:9101` · `9102`를 봄) | tetris 요청이 `503 LLM_UNAVAILABLE` + `Retry-After: 30` | 3.4절 |
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
| `api` | `Stage1Test` (7개) | DTO ↔ JSON 왕복과 snake_case, 입력 검증, 팩 리더(실제 팩 통과 · 고장 팩 4개 · 환경변수 덮어쓰기), 본체 코드에 `delta_` · `tetr` · `chapter` · `챕터` 없음 |
| `comment-engine` | `CommentEngineTest` (6개) | demo(template)와 tetris(server, 가짜 생성 서버)를 엔진 계약으로 호출, 팩 목록, 에러 코드 |
| `comment-engine` | `GenServerDownTest` (1개) | 생성 서버가 전부 꺼지면 그 칩만 503, 다른 칩은 정상 |
| `comment-engine` | `PackRegistryTest` (1개) | 잘못된 팩이 있으면 시작 실패 |
| `finesse-api` | `FinesseApiTest` (3개) | 가짜 엔진을 세우고 계약대로 부르는지, 응답 모양과 에러 코드 변환 |
| `gateway` | `GatewayRoutingTests` (4개) | 엔진 경로 3개가 넘어가는지, 규칙 밖 경로는 404 |

PR을 올리면 GitHub에서 같은 시험이 자동으로 돕니다 (`build-test`).

## 만들다가 발견한 것 (확정안에 반영 필요)

| # | 내용 | 지금은 |
|---|---|---|
| 1 | 지금 llm-server는 zone을 **`높음` · `보통` · `낮음`**으로 받는데, 엔진 계약은 `high` · `normal` · `low`. 확정안 5절 변환 표에 zone이 빠져 있음 | `GenerationProtocol`에서 변환 |
| 2 | 생성 서버 규약을 동결하면 `chapter_id` · `/heavy-chapter`가 본체 코드에 남음 → 9.1절 시험 4("chapter 0건")와 충돌 | 변환을 `GenerationProtocol` 한 곳에 모으고, 그 파일과 `api/generation/`만 시험에서 제외 |
| 3 | 생성 서버가 전부 실패할 때 503으로 끝낼지, `phrases` 대체 문장으로 200을 줄지가 7절 그림만으로는 갈림 | 연결 실패만 → 503, 시간 초과 → 504, 형식 오류만 → 502, 그 밖(5xx 등) → 대체 문장 200 |
| 4 | 엔진은 503에 `Retry-After: 30`을 붙이는데, finesse-api는 그 시간만큼 기다렸다가 한 번 더 부름. light 마감이 40초라 30초를 기다리게 됨 | 그대로 둠. 엔진의 값을 줄일지, finesse-api가 기다리는 시간에 상한을 둘지 정해야 함 |
