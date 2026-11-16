# tetris 칩

TETR.IO 전적을 분석해 코멘트를 만드는 칩입니다. 테트리스에만 해당하는 것은 전부 이 폴더에 있습니다.

| 위치 | 내용 |
|---|---|
| `pack/` | 팩. server 모드, 하이라이트 후보 지표 11개 × 문구 7칸, 섹션 8개 |
| `tetris-analytics/` | 분석 서비스 (7002). 모놀리식 `calc`·통계를 고치지 않고 옮김. [README](tetris-analytics/README.md) |
| `finesse-api/` | 화면이 부르는 API (8092). 통계는 tetris-analytics에 넘기고, 코멘트(light, heavy SSE)는 엔진 계약으로 본체를 부름 |
| `config/` | 두 서비스의 설정 |
| `gateway.yml` | gateway에 꽂는 것: `/api/**` → finesse-api, 헬스 2개 |
| `compose.yml` | 두 서비스 + 생성 서버 자리(small-gen) + 엔진에 알려 줄 생성 서버 주소 |

```
화면 ─▶ gateway /api/** ─▶ finesse-api ─┬─▶ comment-engine (본체) ─▶ 이 칩의 생성 서버
                                        └─▶ tetris-analytics ─▶ TETR.IO
```

## 생성 서버

지금은 시연용 `small-gen`(9200)에 붙어 있습니다. 실제 llm-server(11·13번 PC)로 바꾸려면 `compose.yml` 맨 아래의 `FINESSE_PACK_TETRIS_SERVERS` 한 줄만 고칩니다 (F단계). llm-server 코드는 바꾸지 않습니다.

## 시험

```bash
./gradlew :chips:tetris:finesse-api:test
```

```bash
./gradlew :chips:tetris:tetris-analytics:test
```
