# Finesse 슬롯 프로토타입

> 「MSA 슬롯 구조 설계 방안」(11/12)을 **백엔드 기준**(Java 25 · Spring Boot 4.1.1 · Jackson 3)으로 직접 만들어 본 것.
> 느낌을 보기 위한 프로토타입입니다. 같은 브랜치의 `slot-prototype/`(11/13 호준수 님 추가)과는 별개 폴더라 서로 건드리지 않습니다.

## 한눈에

```
                   ┌──── 슬롯 = api 모듈 ────┐
                   │ ① 엔진 계약              │
                   │   CommentEngineService   │
                   │   DTO · EngineErrorCode  │
                   │ ② 칩 규격                │
                   │   DomainPackReader       │
                   └──────┬────────────┬──────┘
                 호출(구현)│            │구현
 finesse-api ─────────────┘            └──────────── comment-engine ──┬─ template ─ 팩 문장으로 조립 (LLM 없음)
 :8092  EngineClient implements                     :8091            └─ legacy ─── 칸 이름만 바꿔 llm-server로
        CommentEngineService                          │                             (지금은 mock :9000)
                                                      └─ domain-packs/ 를 시작할 때 읽고 검사 (fail fast)
                                                           ├─ demo/   (template)
                                                           └─ tetris/ (legacy)
```

| 폴더 | 역할 | 비유 |
|---|---|---|
| `api/` | 엔진 계약(인터페이스 · DTO · 에러 코드) + 칩 규격(팩 읽기 · 검사 4항목). **분야 단어 0건** | 슬롯 |
| `microservices/comment-engine/` | 계약을 구현. 팩의 `generation.mode`로 길만 나눔. **분야 단어 0건** | 본체 |
| `microservices/finesse-api/` | 지금 백엔드 `CommentService` 자리. 계약으로 엔진을 부르고 응답·에러를 지금 백엔드 모양으로 | 칩 쪽 |
| `domain-packs/` | `demo`(뜻 없는 지표, template), `tetris`(실제 지표 12개, legacy) | 칩 |
| `tools/MockLlmServer.java` | 가짜 llm-server (지금 llm-server와 같은 경로 · 칸 이름) | — |

## 돌려 보기

순서대로 더블클릭 (창 3개가 뜹니다. 끌 때는 창 닫기):

1. `run-1-mock-llm.bat` — 가짜 llm-server :9000
2. `run-2-comment-engine.bat` — 엔진 :8091 (첫 실행은 빌드 때문에 30초쯤)
3. `run-3-finesse-api.bat` — finesse-api :8092
4. PowerShell에서 `powershell -ExecutionPolicy Bypass -File try.ps1` — 요청 7개를 보내고 결과를 보여 줌

테스트만: `run-tests.bat` (29개)

> 이 PC의 1번 서버(8081)와 포트가 겹치지 않습니다. 실제 LLM에 붙이려면 엔진을 켜기 전에
> 환경변수 `ENGINE_LEGACY_SERVERS=http://주소1:포트,http://주소2:포트`를 주면 됩니다 (주소는 파일에 쓰지 말 것).

## 확인한 것 (11/13 실행 결과)

| # | 요청 | 결과 |
|---|---|---|
| 1 | demo 팩 light | `"A가 높습니다"`, `"B가 조금 적습니다"` — **엔진 코드 수정 없이, LLM 없이** 문장이 나옴 |
| 2 | tetris 팩 light | mock이 `{"stat":"delta_app","value":0.05,"zone":"높음"}`을 받음 — 칸 이름 · zone 글자만 바뀜 |
| 3 | tetris 섹션 `attack` | mock이 `{"chapter_id":"attack","data":{...}}`를 받음 |
| 4 | 없는 팩 `lol` | 404 `UNKNOWN_DOMAIN` |
| 5 | `zone: "none"` | 400 `INVALID_PAYLOAD` |
| 6 | finesse-api | calc 선정 결과 → 엔진 → `{"light_summary", "highlights":[{"stat","sentence"}]}` (지금 프론트가 받는 모양) |
| 7 | finesse-api, 지표 없음 | 엔진을 부르지 않고 백엔드 11/9 문장 그대로 |

## 설계 문서와 대응

| 설계 방안 | 코드 |
|---|---|
| 5.1 인터페이스 | `api/.../engine/CommentEngineService.java` — 엔진은 `implements`해서 컨트롤러로, finesse-api는 `implements`해서 HTTP 클라이언트로 |
| 5.2 DTO · snake_case | `api/.../engine/dto/*` — 레코드마다 `@JsonNaming(SnakeCase)`라 앱 설정 없이도 snake_case. 모르는 칸 무시 |
| 5.3 `deadline_ms` = 남은 시간 | finesse-api가 40초 중 남은 시간을 넘김 → 엔진이 절대 마감으로 바꿔 호출 1회 = min(10초, 남은 시간) |
| 5.4 에러 5개 | `EngineErrorCode` → 엔진 `EngineErrorHandler` → finesse-api `ApiErrorHandler`가 `LLM_FORMAT_ERROR` 등 지금 이름으로 |
| 6.5 검사 4항목 | `api/.../pack/DomainPackReader.java` |
| 3장 `generation.mode` | `CommentEngineController` — `switch (pack.mode())`. `if (domain == "tetris")`는 없음 |
| 3장 legacy 어댑터 | `comment-engine/.../generation/LegacyAdapter.java` — llm-server 경로 · 칸 이름 · zone 글자는 `application.yml`에 |
| 8장 시험 4개 | `api/src/test` — ContractJson · ContractValidation · DomainPackReader · NoDomainWords |

legacy 시간 규칙은 지금 백엔드 `LlmClient` 라이트와 같습니다: 문장은 첫 서버, 요약은 둘째 서버로 동시에. 연결 실패면 남은 시간이 10초 이상일 때만 다른 서버로 한 번 더.

## 만들면서 정한 것 (설계에 없던 부분)

| 항목 | 이 프로토타입의 선택 | 관련 미결 |
|---|---|---|
| template 문장 | phrase만 씀(값은 안 넣음, `decimals` 미사용). 요약 = 지표 문장을 `, `로 이음 | 설계 이해 6.5절 "구현할 때 정함" |
| template 섹션 `data` | `{ "<지표>": { "value", "zone" } }` 모양을 기대, 없으면 `no_data` | 미결 3 |
| legacy에서 일부 지표만 실패 | 그 지표는 팩 `no_data` + `fallback: true`. 전부 실패면 에러 코드 | — |
| `reliability` | 백엔드 stats의 `{metric_level, low_sample, suspicious}` 모양으로 받기만 함 | 미결 1 |
| 엔진에 아예 못 닿음 | finesse-api가 `LLM_UNAVAILABLE`로 | — |
| metrics.json의 `_`로 시작하는 키 | 메모로 보고 무시 (tetris 팩 출처 메모용) | — |

## 아직 없는 것

- gateway, Docker/compose(볼륨 마운트), 헤비 8챕터 SSE(엔진은 섹션 1개 단위만 있음), 실패 서버 30초 제외, 추적 헤더(미결 5)
- tetris 팩의 `tr_trend_delta` 라벨은 임시. 나머지 라벨 · 보통 범위 · 소수 자리는 llm-server `names.py` · `light_quartiles.json` · `fallback.py`에서 옮김
