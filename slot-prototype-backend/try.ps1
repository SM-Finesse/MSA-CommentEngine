# 슬롯 프로토타입 맛보기 — 세 서버(mock llm, comment-engine, finesse-api)를 켠 뒤 실행:
#   powershell -ExecutionPolicy Bypass -File try.ps1
# 한글 본문을 UTF-8 바이트로 보낸다 (Git Bash curl은 한글이 깨진 적이 있음)

$engine = 'http://localhost:8091'
$api = 'http://localhost:8092'

function Post($url, $json) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri $url -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes
        $text = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        "  <- $($r.StatusCode) $text"
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
            "  <- $([int]$resp.StatusCode) $($reader.ReadToEnd())"
        } else { "  <- 연결 실패: $($_.Exception.Message)" }
    }
}

function Show($title, $url, $json) {
    ""
    "== $title"
    "  -> POST $url"
    "     $json"
    Post $url $json
}

""
"== 꽂힌 팩 (GET $engine/v1/domains)"
$d = Invoke-WebRequest -UseBasicParsing "$engine/v1/domains"
"  <- " + [System.Text.Encoding]::UTF8.GetString($d.RawContentStream.ToArray())

Show '1. demo 팩 (template — LLM 없이 팩 문장으로)' "$engine/v1/comments/light" `
    '{"domain":"demo","items":[{"metric":"metric_a","value":0.42,"zone":"high"},{"metric":"metric_b","value":-3,"zone":"normal"}],"deadline_ms":30000}'

Show '2. tetris 팩 (legacy — 칸 이름만 바꿔 mock llm-server로)' "$engine/v1/comments/light" `
    '{"domain":"tetris","items":[{"metric":"delta_app","value":0.05,"zone":"high"},{"metric":"delta_opener","value":-0.31,"zone":"low"}],"deadline_ms":35000}'

Show '3. tetris 섹션 1개' "$engine/v1/comments/section" `
    '{"domain":"tetris","section":"attack","data":{"delta_app":0.05,"delta_weighted_app":0.03},"deadline_ms":50000}'

Show '4. 없는 팩 → 404 UNKNOWN_DOMAIN' "$engine/v1/comments/light" `
    '{"domain":"lol","items":[{"metric":"kda","value":1,"zone":"high"}],"deadline_ms":30000}'

Show '5. zone none → 400 INVALID_PAYLOAD' "$engine/v1/comments/light" `
    '{"domain":"demo","items":[{"metric":"metric_a","value":1,"zone":"none"}],"deadline_ms":30000}'

Show '6. finesse-api (백엔드 자리) — calc 선정 결과를 받아 엔진 계약으로, 응답은 지금 백엔드 모양' "$api/api/v1/comment-demo/light" `
    '{"domain":"tetris","highlights":[{"stat":"delta_app","value":0.05,"zone":"high"},{"stat":"delta_plonk","value":0.01,"zone":"none"}]}'

Show '7. finesse-api — 지표가 없으면 엔진을 부르지 않음' "$api/api/v1/comment-demo/light" `
    '{"domain":"tetris","highlights":[{"stat":"delta_plonk","value":0.01,"zone":"none"}]}'
""
