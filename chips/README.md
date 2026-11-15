# 칩

**칩 하나 = 이 폴더 아래의 폴더 하나**입니다. 폴더 이름이 칩의 이름이고, 팩의 `id`와 같아야 합니다.

그 분야에만 해당하는 것은 전부 칩 폴더 안에 둡니다. 본체(`microservices/comment-engine`, `spring-cloud/gateway`, `config-repo`)와 슬롯(`api`)에는 분야 단어를 쓰지 않습니다 (`Stage1Test`가 확인).

## 칩 폴더에 들어가는 것

| 위치 | 필수 | 내용 | 읽는 쪽 |
|---|---|---|---|
| `pack/pack.json`, `pack/metrics.json` | 필수 | 팩. 지표 이름·문구·생성 방식 (슬롯 ② 팩 규격) | comment-engine (시작할 때) |
| `gateway.yml` | 선택 | gateway에 꽂을 경로(`app.routes.<이름>`)와 헬스(`app.health.<이름>`) | gateway (시작할 때) |
| `compose.yml` | 선택 | 칩의 서비스 (API, 분석, 생성 서버). 본체 compose에 겹쳐 씀 | docker compose (루트 `.env`의 `COMPOSE_FILE`) |
| `config/` | 선택 | 칩 서비스의 설정 | 칩의 서비스 |
| 서비스 코드 폴더 | 선택 | 칩의 서비스 코드 (Gradle 모듈이면 루트 `settings.gradle`에 한 줄) | 빌드 |

팩만 있으면 칩입니다 (`demo`). 화면이 부를 자기 경로나 자기 계산이 필요하면 서비스를 더합니다 (`tetris`).

## 지금 있는 칩

| 칩 | 구성 | 설명 |
|---|---|---|
| `tetris` | 팩 + finesse-api + tetris-analytics + 생성 서버 | TETR.IO 전적 코멘트. [tetris/README.md](tetris/README.md) |
| `demo` | 팩만 (template 모드) | 본체가 분야를 모른다는 것을 보이는 가장 작은 칩 |

시험용 칩 `sample`은 `e2e/chips/sample/`에 있고, 칩 꽂기 시험(`e2e/plug-test.sh`)이 여기로 복사했다가 치웁니다.

## 꽂기와 뽑기

| 칩의 구성 | 꽂기 | 뽑기 |
|---|---|---|
| 팩만 | 폴더를 여기에 두고 엔진 재시작 | 폴더를 치우고 엔진 재시작 |
| 서비스가 있음 | 폴더를 여기에 두고, 루트 `.env`의 `COMPOSE_FILE` 끝에 `:chips/<칩>/compose.yml`을 붙인 뒤 `docker compose up -d` 하고 엔진·gateway 재시작 | 반대로 |

어느 쪽이든 본체의 코드·설정·`docker-compose.yml`은 바뀌지 않습니다. 이미지도 다시 만들지 않습니다 (칩 자기 서비스의 이미지는 예외).

Gradle로 빌드하는 서비스가 있는 칩만 루트 `settings.gradle`에 `include` 한 줄이 필요합니다. 빌드할 모듈의 목록이고, 본체의 동작과는 관계없습니다.

## gateway.yml 쓰는 법

```yaml
app.routes:
  my-api:                       # 이름 (다른 칩과 겹치지 않게)
    uri: http://localhost:9000  # 넘길 서비스 주소
    paths:
      - /my/**                  # 그 서비스로 보낼 경로

app.health:
  my-api: http://localhost:9000/actuator/health   # {"status":"UP"} 을 돌려주는 주소

---
spring.config.activate.on-profile: docker         # compose 안에서 쓰는 주소
app.routes.my-api.uri: http://my-api:9000
app.health.my-api: http://my-api:9000/actuator/health
```

경로를 목록(`spring.cloud.gateway...routes`)이 아니라 이름표가 붙은 묶음으로 적는 이유: 스프링 설정에서 목록은 파일끼리 합쳐지지 않고 한 파일의 것으로 통째로 바뀝니다. 묶음은 파일끼리 합쳐져서 칩마다 파일을 따로 둘 수 있습니다.

## 생성 서버 (C안: 칩마다 하나)

server 모드 팩은 `pack.json`의 `generation.servers`에 자기 생성 서버 주소를 적습니다. 띄우는 곳마다 주소가 다르면 엔진 컨테이너의 환경변수 `FINESSE_PACK_<ID>_SERVERS`로 덮어씁니다. 이 환경변수도 칩의 `compose.yml`에 적습니다 (`tetris/compose.yml` 맨 아래).
