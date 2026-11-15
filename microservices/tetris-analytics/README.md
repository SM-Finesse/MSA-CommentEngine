# tetris-analytics

tetris 칩의 분석 서비스입니다. 모놀리식 `backend/`의 계산 코드(`calc`)와 통계 서비스를 **고치지 않고** 옮겼습니다.

| 항목 | 값 |
|---|---|
| 원본 | SM-Finesse/Finesse main `f1e06d1`의 `backend/` |
| 원본 작성 | `calc`와 그 시험: 위성훈(wesunghun) / `StatsService` · `StatsController` · `TetrioClient`: 정한비 |
| 포트 | 7002 (모놀리식은 8081). gateway에는 열지 않고 finesse-api가 안쪽에서 부른다 |
| 경로 | `GET /api/v1/stats/{username}` (모놀리식과 같음) |
| 설정 | `config-repo/tetris-analytics.yml` |

## 옮긴 것

| 구분 | 파일 | 원본과 |
|---|---|---|
| main | 98개 중 96개 (`calc` 76개, 통계 서비스·경로·TETR.IO 클라이언트·설정·DTO·예외 20개) | 내용이 같음 (바이트 대조) |
| 시험 | 41개 (`calc` 35개, 통계 서비스·TETR.IO 클라이언트·사용자명·앱 기동 6개) | 내용이 같음 (바이트 대조) |
| 설정 | `config-repo/tetris-analytics.yml` | 원본 `application.yml`에서 LLM 부분(`llm.*`)만 뺌. 바꾼 값은 포트와 이름 |

옮기는 파일은 통계 경로(`StatsController`, `StatsService`)와 `calc`에서 시작해 import와 같은 패키지 참조, 빈 주입을 끝까지 따라가 골랐습니다. 코멘트·LLM 쪽 파일 17개와 그 시험 11개는 옮기지 않았습니다 (finesse-api와 comment-engine이 맡음).

## 새로 만든 것 (원본에 없음)

| 파일 | 이유 |
|---|---|
| `TetrisAnalyticsApplication` | 시작 클래스. 애너테이션은 원본 `BackendApplication`과 같음 |
| `config/TetrioWebClientConfig` | 원본 `config/WebClientConfig`에서 TETR.IO 부분만 떼어 옴. 원본은 LLM용 빈도 같은 파일에 있어서 그대로 옮기면 LLM 설정까지 따라온다. 본문은 원본과 같음 |
| `build.gradle` | 원본 의존성에서 통계에 필요한 것 그대로 + 헬스체크·추적 |
| `Dockerfile` | 다른 서비스와 같음 |

## 시험

```bash
./gradlew :microservices:tetris-analytics:test
```

`TetrIoApiLiveTest`, `AnalyzeLiveTest`는 실제 TETR.IO를 부르는 시험이라 기본으로는 건너뜁니다. 돌리려면 `-Dtetrio.live=true -Dtetrio.username=<이름>`을 시험 JVM에 넘겨야 합니다.

## 실행

```bash
./gradlew :microservices:tetris-analytics:bootRun
```

```bash
curl http://localhost:7002/api/v1/stats/<TETR.IO 이름>
```

모놀리식 `GET /api/v1/stats/<이름>`과 같은 응답이 나와야 합니다. TETR.IO 데이터는 시간에 따라 바뀌므로 두 쪽을 같은 때에 불러 비교합니다.
