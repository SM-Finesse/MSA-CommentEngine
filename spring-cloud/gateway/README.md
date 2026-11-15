# gateway

본체의 입구입니다. 수업 레포(`spring-microservice-boot4` Chapter23)의 `spring-cloud/gateway`에서 인증과 HTTPS를 걷어낸 것입니다.

| 항목 | 값 |
|---|---|
| 포트 | 8080 (컨테이너 안에서는 관리 포트 4004를 따로 씀) |
| 넘기는 경로 | `/v1/comments/**`, `/v1/domains` → comment-engine (본체)<br>`/api/**` → finesse-api (칩 쪽 API) |
| 경로 규칙 위치 | `config-repo/gateway.yml` |
| 넘길 곳 주소 | `app.engine-url` (기본 `http://localhost:7001`), `app.finesse-api-url` (기본 `http://localhost:8092`) |
| 주소 기억 시간 | `app.dns-cache-seconds` 5초. 엔진이 새 IP로 다시 떠도 5초 안에 따라감 (`DnsCacheConfiguration`) |

아직 없는 것: 헬스체크 모음(`HealthCheckConfiguration`).

## 빌드와 시험

레포 루트에서 실행합니다.

```bash
./gradlew :spring-cloud:gateway:test
```

## 실행

엔진을 먼저 띄운 뒤(루트 `README.md` 참고) 레포 루트에서 실행합니다.

```bash
./gradlew :spring-cloud:gateway:bootRun
```

```bash
curl http://localhost:8080/v1/domains
```

엔진에 직접 부른 `curl http://localhost:7001/v1/domains`와 같은 답이 나오면 됩니다.

IntelliJ의 실행 버튼으로 띄우면 작업 폴더가 달라 `config-repo`를 못 찾습니다. 환경변수 `CONFIG_DIR`에 `config-repo` 폴더의 경로를 넣어 주세요.
