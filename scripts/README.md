# 测试脚本

四个文件，按场景挑一个用。共同点：**都不需要安装任何依赖**（除了 `local-dev.sh` 需要 Go）。

| 文件 | 场景 | 需要什么 |
|---|---|---|
| `send-test.sh` | 往中转层发消息，一次看全健康检查 + 投递 + 落库回查 | curl（Git Bash / Linux / macOS 自带） |
| `send-test.ps1` | 同上，Windows 原生 PowerShell 版 | PowerShell 5.1+ |
| `watch.js` | 单独挂着实时监听 WebSocket | Node 22+ |
| `test.http` | 点一下发请求，可视化改参数 | VS Code REST Client 插件 |
| `local-dev.sh` | 本地起一套隔离实例做全链路测试 | Go 1.22+ |

## 最常用：往线上发一条

```bash
# Git Bash / macOS / Linux
export RELAY_TOKEN=你的token
./send-test.sh

# 想同时看实时推送（先挂 WS，再发消息，3 秒后自动退出）
./send-test.sh -w

# 改标题、正文、级别、来源
./send-test.sh -t "部署完成" -b "v1.2.0 已上线" -l warn -s ci

# 连发 5 条（每条自动加时间戳，不会被幂等拦住）
./send-test.sh -n 5
```

Windows 原生：

```powershell
$env:RELAY_TOKEN = "你的token"
.\send-test.ps1
.\send-test.ps1 -Title "部署完成" -Body "v1.2.0 已上线" -Level warn -Count 3
```

token 不给也行——脚本会自动读 `../server/.env` 里的 `RELAY_TOKEN`。

## 只监听，不发

```bash
node watch.js https://your-domain.com/hookbox 你的token
```

开着它，然后去 GitHub 点一次 "Redeliver"，或者从别的终端发请求，能立刻看到推送内容。断线会自动重连。

## 本地起一套隔离实例

```bash
./local-dev.sh              # 127.0.0.1:18080
./local-dev.sh -p 19000     # 换端口
```

会编译 `server/` 并在本地跑起来，数据写 `server/local-data/hookbox.db`，**完全不碰线上和服务器**。启动时会打印 token 和下一步该跑什么命令。

适合：改完服务端代码想先验证、或者线上出问题时做对照。

## 三个容易踩的点

**1. 同样的 body 发两次，第二次会 `duplicate:true`，这是设计如此。** 服务端用 `source + payload hash` 做幂等去重，防的是平台超时重投。要发多条相同内容，得让 body 有差异（脚本的 `-n` 参数已经自动加了时间戳）。

**2. PowerShell 5.1 传中文会乱码，除非手动转 UTF-8 字节。** `send-test.ps1` 里已经处理了（`[System.Text.Encoding]::UTF8.GetBytes()`）。如果你自己写 `Invoke-RestMethod -Body $json`，中文会变成 `???`——这是 PS 5.1 默认用 ISO-8859-1 的老问题，不是服务端的锅。

**3. `.ps1` 文件必须带 UTF-8 BOM。** 没有 BOM 的话，PowerShell 5.1 会按 GBK 解析，中文字符串直接变成语法错误。改这个文件时注意别把 BOM 弄丢。

## 返回码对照

| 返回 | 含义 | 下一步 |
|---|---|---|
| `200 {"ok":true,"duplicate":false}` | 成功入库并已广播 | 看手机是否收到 |
| `200 {"ok":true,"duplicate":true}` | 内容重复，被幂等拦截 | 正常行为，换个 body 再试 |
| `401 unauthorized` | token 不对 | 检查 `RELAY_TOKEN` |
| `401 bad_signature` | 来源配了 secret，但签名不对 | 用 URL token 兜底，或补正确签名 |
| `401 missing_signature` | 来源配了 secret，但没带签名头 | 同上 |
| `413` | payload 太大 | 默认上限 256KB |
| `429` | 触发限流 | 等一会儿 |
| 连接超时 | 服务没通 | `docker compose -p hookbox ps` 看容器 |
