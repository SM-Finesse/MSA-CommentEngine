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

### 시간에 민감한 시험 2개 (작성: 정한비)

옮긴 시험 41개 중 2개가 CI에서 실패했습니다. 옮긴 코드가 달라서가 아니라, 시험이 시간에 기대는 부분이 있어서입니다. **시험 코드는 고치지 않았습니다.** 고치는 것은 원본 레포에서 담당자가 하고, 고쳐지면 다시 옮깁니다.

| 시험 | 실패 내용 | 원인 |
|---|---|---|
| `TetrioClientSharedLimiterTest` | 마지막 단언. 기다린 시간 180ms, 기대는 300ms 이상. 데우기 없이 2번 돌려 2번 실패, 데우기를 넣고 3번 모두 통과 | 시험이 "백엔드의 HTTP 호출이 100ms 안에 끝난다"고 가정합니다. 모놀리식에서는 같은 패키지의 LLM 쪽 WebClient 시험(`LightCommentClientTest`, `LlmClientTest`)이 먼저 돌아 Netty가 데워진 상태였습니다. 이 모듈에는 그 시험이 없어서 첫 HTTP 호출이 이 시험 안에서 일어나고, 약 220ms가 걸렸습니다 |
| `StatsServiceBusyTest` 첫 번째 시험 | 마지막 단언. `UserNotFoundException`을 기대했는데 `ServerBusyException`. 5번 돌려 2번 실패 (데우기와 상관없음) | 수집 자리는 수집 스레드가 결과를 넘긴 **다음에** 반납됩니다 (`finally { release() }`). 시험은 결과를 받자마자 다음 요청을 보내므로, 반납보다 먼저 도착하면 BUSY가 납니다. 같은 파일의 두 번째 시험은 이 틈 때문에 50ms 간격으로 다시 시도하는데, 첫 번째 시험에는 그 처리가 없습니다 |

이 모듈에서 한 것:

| 무엇 | 어디 |
|---|---|
| 시험 JVM이 뜰 때 WebClient로 로컬 호출을 3번 해서 데움. 모놀리식과 같은 조건을 만드는 것 | `src/test/java/com/finesse/msa/testsupport/WebClientWarmup.java` (새 파일, 시험 전용) |
| 위 2개 시험만, 실패하면 최대 4번 다시 돌림. 다른 시험은 실패하면 바로 실패 | `build.gradle`의 `retry` (Gradle `test-retry` 플러그인) |
| 다시 돌려서 통과한 시험은 CI 로그에 `FLAKY`로 표시 (11/15 실행에서 `StatsServiceBusyTest`가 1번 실패 후 통과한 것을 확인) | `.github/workflows/build-test.yml`의 "시험 수 세기" |

원본에서 고칠 곳 (제안, 담당자 판단):

| 시험 | 제안 |
|---|---|
| `TetrioClientSharedLimiterTest` | 두 번째 간격을 "백엔드 호출이 차례를 얻은 시각"부터 재거나, 기준에서 HTTP 호출 시간을 빼기 |
| `StatsServiceBusyTest` 첫 번째 시험 | 마지막 확인도 두 번째 시험처럼, BUSY면 잠깐 기다렸다 다시 시도하기 |

## 실행

```bash
./gradlew :microservices:tetris-analytics:bootRun
```

```bash
curl http://localhost:7002/api/v1/stats/<TETR.IO 이름>
```

모놀리식 `GET /api/v1/stats/<이름>`과 같은 응답이 나와야 합니다. TETR.IO 데이터는 시간에 따라 바뀌므로 두 쪽을 같은 때에 불러 비교합니다.
