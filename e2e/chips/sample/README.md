# sample 칩 (시험용)

칩 꽂기 시험(`e2e/plug-test.sh`)에서만 쓰는 칩입니다. 실제 칩이 아닙니다.

실제 칩(`chips/tetris/`)과 같은 모양이라, 새 칩을 만들 때 가장 작은 본보기로 볼 수 있습니다.

| 파일 | 내용 |
|---|---|
| `pack/` | 팩 (지표 1개, server 모드) |
| `compose.yml` | 이 칩의 서비스: 생성 서버 `sample-gen`(9300), API 서비스 `sample-api`(9310) |
| `gateway.yml` | gateway에 꽂는 경로(`/sample/**`)와 헬스 |
| `api/SampleApi.java` | API 서비스 (파일 하나짜리 Java) |
| `light-request.json` | 시험이 엔진에 보내는 요청 |
