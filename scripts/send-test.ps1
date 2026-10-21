# HookBox 本地测试脚本（PowerShell 版，Windows 原生，不依赖 Git Bash）
#
# 用法：
#   .\send-test.ps1                          发一条默认测试消息
#   .\send-test.ps1 -Title 标题 -Body 正文 -Level warn
#   .\send-test.ps1 -Count 3                 连发 3 条
#   .\send-test.ps1 -Health                  只做健康检查
#
# 地址与 token 来源优先级：参数 > 环境变量 > ..\server\.env
#
# ⚠️ 关键坑：Windows PowerShell 5.1 的 Invoke-RestMethod 默认用 ISO-8859-1
#    编码请求体，中文会变成 "???"。必须自己转成 UTF-8 字节数组再传。

[CmdletBinding()]
param(
    [string]$Title = "本地测试",
    [string]$Body  = "",
    [ValidateSet("info", "warn", "error")]
    [string]$Level = "info",
    [string]$Source = "manual-test",
    [string]$Url   = "",
    [string]$Token = "",
    [int]$Count    = 1,
    [switch]$Health
)

$ErrorActionPreference = "Stop"

# ---------- 读配置 ----------
$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) "server\.env"
if (-not $Url -and -not $env:HOOKBOX_URL -and (Test-Path $envFile)) {
    $line = Select-String -Path $envFile -Pattern '^HOOKBOX_URL=' -ErrorAction SilentlyContinue
    if ($line) { $Url = ($line.Line -split '=', 2)[1] }
}
if (-not $Token -and -not $env:RELAY_TOKEN -and (Test-Path $envFile)) {
    $line = Select-String -Path $envFile -Pattern '^RELAY_TOKEN=' -ErrorAction SilentlyContinue
    if ($line) { $Token = ($line.Line -split '=', 2)[1] }
}

if (-not $Url)   { $Url = if ($env:HOOKBOX_URL) { $env:HOOKBOX_URL } else { "https://your-domain.com/hookbox" } }
if (-not $Token) { $Token = $env:RELAY_TOKEN }
$Url = $Url.TrimEnd('/')

if (-not $Token) {
    Write-Host "❌ 没有 token。三种给法：" -ForegroundColor Red
    Write-Host "   1) `$env:RELAY_TOKEN = 'xxx'"
    Write-Host "   2) 在 server\.env 里写 RELAY_TOKEN=xxx"
    Write-Host "   3) .\send-test.ps1 -Token xxx"
    exit 1
}

$masked = $Token.Substring(0, 8) + "…" + $Token.Substring($Token.Length - 4)
Write-Host "→ 目标 $Url" -ForegroundColor Cyan
Write-Host "→ token $masked（已隐藏中段）" -ForegroundColor DarkGray
Write-Host ""

# ---------- 健康检查 ----------
Write-Host "【健康检查】"
try {
    $h = Invoke-RestMethod -Uri "$Url/healthz" -TimeoutSec 12 -UseBasicParsing
    Write-Host "  ✅ 200  $h" -ForegroundColor Green
} catch {
    Write-Host "  ❌ 连不上：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "     先确认容器在跑：docker compose -p hookbox ps"
    exit 1
}
if ($Health) { exit 0 }
Write-Host ""

# ---------- 投递 ----------
Write-Host "【投递】"
$fail = 0
for ($i = 1; $i -le $Count; $i++) {
    if (-not $Body) { $b = "链路测试 $(Get-Date -Format 'HH:mm:ss').$i" }
    else            { $b = "$Body [$(Get-Date -Format 'HH:mm:ss').$i]" }

    $obj  = @{ title = $Title; body = $b; level = $Level }
    $json = $obj | ConvertTo-Json -Compress
    # UTF-8 字节数组，绕开 PS 5.1 的 Latin-1 默认编码
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)

    try {
        $r = Invoke-RestMethod -Uri "$Url/hook/$Source`?token=$Token" `
             -Method Post -ContentType "application/json; charset=utf-8" `
             -Body $bytes -TimeoutSec 12 -UseBasicParsing
        if ($r.duplicate) {
            Write-Host "  ⚠️  [$i] 200 重复消息（同样内容已发过，被幂等拦截）" -ForegroundColor Yellow
        } else {
            Write-Host "  ✅ [$i] 200 id=$($r.id)" -ForegroundColor Green
        }
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        Write-Host "  ❌ [$i] HTTP $code  $($_.Exception.Message)" -ForegroundColor Red
        $fail++
    }
    if ($i -lt $Count) { Start-Sleep -Milliseconds 400 }
}

# ---------- 回查历史 ----------
Write-Host ""
Write-Host "【落库回查】最近 3 条"
try {
    $hist = Invoke-RestMethod -Uri "$Url/api/messages?token=$Token&limit=3" -TimeoutSec 12 -UseBasicParsing
    foreach ($m in $hist.messages) {
        $t = [DateTimeOffset]::FromUnixTimeMilliseconds($m.ts).ToLocalTime().ToString("HH:mm:ss")
        $lv = $m.level.PadRight(5)
        Write-Host ("  {0}  [{1}] {2,-12} {3}" -f $t, $lv, $m.source, $m.title)
    }
} catch {
    Write-Host "  （回查失败，不影响投递结论）" -ForegroundColor DarkGray
}

Write-Host ""
if ($fail -eq 0) {
    Write-Host "结论：中转层正常。若手机没收到 → 问题在 App 侧（服务未开 / 未连上 / 被系统杀）。" -ForegroundColor Green
} else {
    Write-Host "结论：有 $fail 条投递失败，看上面的 HTTP 码。" -ForegroundColor Red
}
exit $fail
