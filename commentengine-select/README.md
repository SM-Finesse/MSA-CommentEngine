# CommentEngine 서비스 선택 화면

엔진 도메인의 첫 화면입니다. "어느 서비스를 사용하시겠습니까?"에서 칩을 고르면 그 칩의 주소로 이동합니다.

빌드 없는 정적 사이트(HTML/CSS/JS)라 어떤 웹 서버(nginx 등)로도 그대로 서빙할 수 있습니다.

## 파일

| 파일 | 내용 |
|---|---|
| `index.html` | 화면 뼈대 |
| `style.css` | 스타일 (라이트/다크) |
| `app.js` | `config.json`을 읽어 카드 렌더, 키보드·클릭 이동 |
| `config.json` | **사이트 이름과 칩 목록. 코드 수정 없이 여기만 바꾸면 됩니다** |

## config.json

```json
{
  "siteName": "CommentEngine",
  "question": "어느 서비스를 사용하시겠습니까?",
  "chips": [
    { "id": "finesse", "name": "Finesse", "description": "테트리스 플레이 분석 · 코멘트",
      "url": "https://msa.finesse-lab.com", "enabled": true, "art": "blocks" },
    { "id": "next", "name": "준비 중", "url": "", "enabled": false, "art": "soon" }
  ],
  "emptySlots": 2
}
```

| 필드 | 설명 |
|---|---|
| `siteName` | 좌상단·탭 제목에 표시 |
| `question` | 안내 문구 |
| `chips[].name` / `description` | 카드 이름 / 아래 캡션 설명 |
| `chips[].url` | 이동할 주소. 도메인이 정해지면 `https://finesse.<엔진 도메인>`으로 교체 |
| `chips[].enabled` | `false`이거나 `url`이 비면 "준비 중" 비활성 카드 |
| `chips[].art` | `blocks`(블록 그림), `soon`(준비 중 무늬), 생략 시 이름 첫 글자 + `color` 배경 |
| `chips[].image` | (선택) 카드 이미지 경로. 지정하면 `art`보다 우선 |
| `emptySlots` | 오른쪽에 깔리는 빈 자리 개수(장식) |

`config.json`은 `cache: no-store`로 읽으므로 파일만 교체하면 새로고침 즉시 반영됩니다. 컨테이너로 띄울 때는 이 파일만 볼륨으로 마운트해 바꿀 수 있습니다.

## 로컬 실행

`fetch`로 설정을 읽기 때문에 파일을 더블클릭(`file://`)하면 동작하지 않습니다. 아무 정적 서버로 띄워 주세요.

```bash
python -m http.server 8080
# 또는
npx serve .
```

→ http://localhost:8080

## 조작

- 마우스: 카드에 올리면 선택, 클릭하면 이동
- 키보드: `←` `→` 선택, `Enter` 이동
- 하단 동그란 버튼: 테마(라이트/다크) 전환, 목록 새로고침
