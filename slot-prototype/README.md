# 슬롯 프로토타입

[MSA 슬롯 구현 확정안](../docs/MSA_슬롯_구현_확정안.md)을 **최소한으로** 돌려 보는 실험용 폴더입니다. 본체 하나(comment-engine)에 칩 두 개(tetris, demo)를 꽂아, 슬롯이 어떤 느낌인지 손으로 확인할 수 있습니다.

> 실제 서비스 코드가 아닙니다. gateway, finesse-api, tetris-analytics, docker compose는 넣지 않았습니다. 엔진 계약은 칩 대신 `try.ps1`(curl)로 직접 부릅니다.

## 무엇이 어디에 있나

```
slot-prototype/
├── api/                                  ← 슬롯 (약속 3개)
│   └── com/finesse/api/
│       ├── engine/      ① 엔진 계약   CommentEngineService(경로), dto/, EngineErrorCode
│       ├── generation/  ③ 생성 서버 규약   지금 llm-server API 그대로 (동결)
│       └── pack/        ② 팩 규격     DomainPackReader (시작 시 검사 4개)
├── microservices/comment-engine/         ← 본체
│   └── com/finesse/engine/
│       ├── EngineController       ① 구현. 검증 → 팩 찾기 → 라우터
│       ├── PackRegistry           시작할 때 팩 전부 읽기. 하나라도 틀리면 안 뜸
│       ├── GenerationRouter       mode = template / server 로 갈라짐
│       ├── TemplateGenerator      phrases로 문장 조립 (template 모드 + 대체 문장)
│       ├── ServerGenerator        라운드로빈 · 30초 제외 · 재시도 · 마감
│       └── GenerationProtocol     ③ 어댑터. metric→stat, high→높음 같은 변환은 여기 한 곳
├── domain-packs/                         ← 칩 (엔진 이미지 밖)
│   ├── tetris/   server 모드, 지표 3개만 (실제는 11개)
│   └── demo/     template 모드
├── small-gen/SmallGenServer.java         ← 두 번째 칩의 작은 생성 서버 (③ 3개 경로만)
└── try.ps1                               ← 엔진 계약으로 불러 보는 스크립트
```

## 실행

PowerShell 창 3개를 씁니다. Java 25는 Gradle이 알아서 찾습니다 (backend와 같은 설정).

```powershell
# 창 1 — 작은 생성 서버 (tetris 칩의 llm-server 대신)
cd slot-prototype
java small-gen/SmallGenServer.java 9200

# 창 2 — 본체. tetris 팩의 서버 주소를 환경변수로 small-gen으로 돌림
cd slot-prototype
$env:FINESSE_PACK_TETRIS_SERVERS = "http://localhost:9200"
.\gradlew :microservices:comment-engine:bootRun

# 창 3 — 불러 보기
cd slot-prototype
.\try.ps1
```

시작 로그에 `pack loaded: demo ...`, `pack loaded: tetris ...`가 보이면 슬롯에 칩 두 개가 꽂힌 것입니다.

## 느껴 볼 것 6가지

| # | 해 볼 것 | 보이는 것 | 확정안 |
|---|---|---|---|
| 1 | 그냥 `try.ps1` | demo는 `phrases` 문장, tetris는 `[small-gen:9200] …` 문장. **같은 엔진, 같은 경로** | 2장 |
| 2 | 창 2에서 환경변수를 빼고 재시작 (tetris가 `localhost:9101` · `9102`를 봄) | tetris 요청이 `503 LLM_UNAVAILABLE` + `Retry-After: 30` | 3.4절 |
| 3 | small-gen을 `$env:FAIL_RATE=100`으로 띄움 | tetris 문장이 `phrases` 대체 문장으로 바뀌고 `fallback: true` | 7절 |
| 4 | small-gen을 `$env:FAIL_RATE=50`으로 띄움 | 엔진 로그에서 실패 → 재시도 → 성공이 보임. 일부 항목만 `fallback: true`일 때도 있음 | 7.2절 |
| 5 | `domain-packs/demo/metrics.json`에서 `zero` 한 칸을 `""`로 바꾸고 재시작 | 엔진이 `metric_b.phrases.zero is blank`로 **아예 안 뜸** | 4.5절 |
| 6 | **4단계 흉내** — `domain-packs/demo/pack.json`의 `generation`을 `{ "mode": "server", "servers": ["http://localhost:9200"] }`로 바꾸고 재시작 | demo 문장이 small-gen에서 나옴. **엔진 코드 수정 0줄** | 9절 4단계 |

> 5 · 6번은 다 본 뒤에 파일을 원래대로 돌려 두세요.

마감 초과(`504 LLM_TIMEOUT`)를 보려면 small-gen을 `$env:DELAY_MS=4000`으로 띄우고, 요청의 `deadline_ms`를 `1000`으로 줄입니다.

## 시험 (1단계 완료 기준)

```powershell
.\gradlew :api:test
```

| # | 시험 | 메서드 |
|---|---|---|
| 1 | DTO ↔ JSON 왕복, 키가 모두 snake_case | `test1_*` |
| 2 | 빈 items · `deadline_ms: 0` · `zone: none` · NaN 거절, 40000 통과 | `test2_validation` |
| 3 | 실제 팩 통과, 고장 팩 4개가 각자 맞는 메시지로 실패, 환경변수 덮어쓰기 | `test3_*` |
| 4 | 본체 코드에 `delta_` · `tetr` · `chapter` · `챕터` 없음 | `test4_noDomainWordsInBody` |

## 만들다가 발견한 것 (확정안에 반영 필요)

| # | 내용 | 이 프로토타입에서는 |
|---|---|---|
| 1 | 지금 llm-server는 zone을 **`높음` · `보통` · `낮음`**으로 받는데, 엔진 계약은 `high` · `normal` · `low`. 확정안 5절 변환 표에 zone이 빠져 있음 | `GenerationProtocol`에서 변환 |
| 2 | 생성 서버 규약을 동결하면 `chapter_id` · `/heavy-chapter`가 본체 코드에 남음 → 9.1절 시험 4("chapter 0건")와 충돌 | 변환을 `GenerationProtocol` 한 곳에 모으고, 그 파일과 `api/generation/`만 시험에서 제외 |
| 3 | 생성 서버가 전부 실패할 때 503으로 끝낼지, `phrases` 대체 문장으로 200을 줄지가 7절 그림만으로는 갈림 | 연결 실패만 → 503, 시간 초과 → 504, 형식 오류만 → 502, 그 밖(5xx 등) → 대체 문장 200 |
