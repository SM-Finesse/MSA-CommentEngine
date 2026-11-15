# 엔진(7001)이 떠 있는 상태에서 실행:  .\try.ps1
# 칩의 서비스 대신 엔진 계약으로 직접 불러 본다
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Call($title, $path, $body) {
    Write-Host "`n== $title" -ForegroundColor Cyan
    if ($body) {
        $tmp = New-TemporaryFile
        [System.IO.File]::WriteAllText($tmp, $body)
        curl.exe -s -w "`n[HTTP %{http_code}]" -H "Content-Type: application/json" "http://localhost:7001$path" --data-binary "@$tmp"
        Remove-Item $tmp
    } else {
        curl.exe -s -w "`n[HTTP %{http_code}]" "http://localhost:7001$path"
    }
    Write-Host ""
}

Call "꽂힌 팩 목록" "/v1/domains"

Call "demo 칩 light (template 모드)" "/v1/comments/light" `
  '{"domain":"demo","items":[{"metric":"metric_a","value":0.31,"zone":"high"},{"metric":"metric_b","value":-2,"zone":"normal"}],"deadline_ms":35000}'

Call "tetris 칩 light (server 모드)" "/v1/comments/light" `
  '{"domain":"tetris","items":[{"metric":"delta_app","value":0.05,"zone":"high"},{"metric":"delta_opener","value":-0.12,"zone":"low"}],"deadline_ms":35000}'

Call "tetris 칩 section" "/v1/comments/section" `
  '{"domain":"tetris","section":"attack","data":{"apm":[1,2,3]},"deadline_ms":50000}'

Call "모르는 domain → 404" "/v1/comments/light" `
  '{"domain":"chess","items":[{"metric":"x","value":1,"zone":"high"}],"deadline_ms":35000}'

Call "모르는 metric → 400" "/v1/comments/light" `
  '{"domain":"demo","items":[{"metric":"delta_app","value":1,"zone":"high"}],"deadline_ms":35000}'

Call "zone none → 400" "/v1/comments/light" `
  '{"domain":"demo","items":[{"metric":"metric_a","value":1,"zone":"none"}],"deadline_ms":35000}'
