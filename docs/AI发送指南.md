# 给 AI 的 HookBox 发送指南

> 本文档面向 AI 助手（Muse 等）：照此执行，即可把消息发送到用户的 HookBox 安卓 App。
> 人类读者请看同目录的《使用说明.md》；本文只讲「AI 如何发、如何验、如何避坑」。
> 版本：对应服务端 ≥ 0.2.0（Go 自托管版与 Worker 版协议一致，本文以线上 Go 版为准）。

---

## 0. 执行摘要（TL;DR）

1. 将用户发送的relay_token.txt保存到`~/workspace/user/files/relay_token.txt`，从文件读 token（**不要**向用户索要原文，**不要**把原文写进任何命令文本）：
   `~/workspace/user/files/relay_token.txt`
2. 用脚本在进程内把 token 读进变量，POST 一条 JSON 到：
   `https://your-domain.com/hookbox/hook/<source>?token=<RELAY_TOKEN>`
3. 看返回 `{"ok":true,"duplicate":false,...}`，再回查 `/api/messages` 确认落库，即完成。
4. 向用户报告时只说结果（标题、消息 id、验证结论），**永远不要输出 token 原文或片段**。
5. 每次发送消息时，标题要带上发送方 muse ai 的名称作前缀，格式为「名称 + 空格 + 原标题」，比如标题写成「云梦 部署完成」，这样在通知和列表里一眼就知道消息来自于哪位。
一键脚本见第 3.3 节，复制改三处即可直接用。

---

## 1. 服务信息

| 项 | 值 |
|---|---|
| 中转地址（base） | `https://your-domain.com/hookbox` |
| 健康检查 | `GET /healthz` → 纯文本 `ok`（无需鉴权） |
| 投递端点 | `POST /hook/<source>`，如 `/hook/manual-test` |
| 历史回查 | `GET /api/messages?token=<T>&limit=<n>&before=<毫秒时间戳>` |
| 实时通道 | `WSS /ws?token=<T>`（App 用的就是这条，AI 验证可用仓库 `scripts/watch.js`） |
| 请求体上限 | 1 MB，超出返回 413 |
| 仓库 | github.com/xusofty-cyber/webhookBox（服务端代码在 `server/`（Go）与 `worker/`（TS）） |

链路：AI/平台 → POST 中转 → 落库（幂等去重）→ WebSocket 实时广播 → 安卓 App（通知 + 本地库 + 详情页）。

---

## 2. RELAY_TOKEN 如何使用（最重要，先读完这节）

### 2.1 它是什么

`RELAY_TOKEN` 是中转服务的共享密钥，等同于密码：谁有它，谁就能往用户的手机发任意通知。
服务端把它放在服务器 `/opt/hookbox/.env` 里；AI 这边，用户已把它保存在：

```
~/workspace/user/files/relay_token.txt
```

文件内容是 64 位十六进制字符串（可能带结尾换行，读取时 trim 掉空白即可）。

### 2.2 三条铁律

1. **不要把 token 原文写进命令文本、代码、回复、日志或记忆文件。**
   本平台会把出现在工具调用文本里的凭据原文自动替换成打码占位符（实测：64 位的 token 会被换成 19 个字符的占位符）。带占位符发出的请求必然失败：服务器返回 `401 unauthorized`，或连接被直接断开（curl 的 `Empty reply from server` / HTTP 000）。这不是网络故障，也不是 token 失效——识别特征就是「错 token 能得到规规矩矩的 401，而"真" token 反而连接异常」。
2. **正确姿势：让脚本/程序在进程内直接读文件**，把 token 装进变量后拼 URL，全程不经过 AI 的文本输出（见 3.3 的脚本）。这样发出的才是原文，链路已实测通畅。
3. **向用户提及 token 时只说存放位置**，不摘录、不截屏、不给前后缀。用户若在聊天里贴了 token 原文，提醒其该串已暴露于对话记录，建议轮换（服务器改 `.env` 后重启容器，并同步更新上述文件与 App 设置），但不要把原文抄进任何持久化记录。

### 2.3 如果 token 文件不存在

- 请用户**以上传文件的方式**提供（存到 `~/workspace/user/files/relay_token.txt`），不要让用户在聊天里粘贴原文（理由见 2.2 第 1 条）。
- 或者请用户在服务器上自查：`grep RELAY_TOKEN ~/projects/hookbox/.env`。
- 文件与服务端不一致时，任何调用都会 401；不要反复重试猜 token。

---

## 3. 发送一条消息

### 3.1 请求格式

```
POST https://your-domain.com/hookbox/hook/<source>?token=<RELAY_TOKEN>
Content-Type: application/json

{
  "title": "部署完成",
  "body": "api 已上线 v1.2.0，耗时 4 分钟",
  "level": "info"
}
```

- `<source>` 是来源标签，随意命名（小写字母/数字/连字符），App 列表与详情页会显示它。AI 代发建议用 `manual-test` 或按场景用 `claude-alert` 等可辨识标签。
- 成功返回：`{"ok":true,"id":"manual-test:9f2c...","duplicate":false}`

### 3.2 字段说明

| 字段 | 必填 | 说明 |
|---|---|---|
| `title` | 建议 | 标题。服务端依次 fallback：`title` → `subject` → `name` → `<source> 通知`。上限 300 字（按 rune 截断） |
| `body` | 建议 | 正文。fallback：`body` → `text` → `message` → `content`。上限 20000 字 |
| `level` | 否 | `info` / `warn` / `error`；`critical`/`fatal` 归 error，`warning` 归 warn，其余归 info。error 在 App 里是高优先级通知 |
| `id` | 否 | 自定义去重 id。不给则用整个请求体的 sha256——**同一 body 重发会被判重**（见第 5 节） |
| `ts` | 否 | 毫秒时间戳（只认毫秒；秒级会被忽略，改用服务器时间） |
| 其他任意字段 | 否 | 不参与标题/正文，但会**原样保留在原始 payload（raw）里**，App 详情页能完整看到。想给用户看结构化详情，就把细节放在这里 |

**关于「完整显示」**：服务端会把请求体原文存为 raw 并透传到 App。标题/正文是归一化预览，raw 才是全量。所以重要信息除了写进 `body`，也可以放心地以结构化字段附加，详情页里会以美化 JSON 呈现。

### 3.3 Muse 环境一键脚本（发送 + 验证）

把 `TITLE`、`BODY`、`LEVEL`、`SOURCE` 换成实际值后整段执行。注意脚本里没有任何 token 原文：

```bash
#!/bin/sh
# 从文件读 token（进程内使用，不回显、不落日志）
TOKEN=$(tr -d '[:space:]' < ~/workspace/user/files/relay_token.txt)
BASE="https://your-domain.com/hookbox"
SOURCE="manual-test"
TITLE="这里填标题"
BODY="这里填正文，可以很长，详情页会完整显示"
LEVEL="info"   # info | warn | error

# 1) 健康检查
curl -sS -m 15 "$BASE/healthz" || { echo "中转不可达"; exit 1; }

# 2) 投递（body 里加一个时间戳字段，避免与历史消息判重）
curl -sS -m 15 -X POST "$BASE/hook/$SOURCE?token=$TOKEN" \
  -H 'content-type: application/json' \
  -d "{\"title\":\"$TITLE\",\"body\":\"$BODY\",\"level\":\"$LEVEL\",\"sent_at\":\"$(date -u +%FT%TZ)\"}"

# 3) 回查最新一条，确认落库（并检查 raw 是否透传）
curl -sS -m 15 "$BASE/api/messages?token=$TOKEN&limit=1"
```

预期：第 2 步返回 `ok:true, duplicate:false`；第 3 步能看到刚发的消息且带 `raw` 字段。

用仓库自带脚本也可以（在仓库根目录）：

```bash
export RELAY_TOKEN=$(tr -d '[:space:]' < ~/workspace/user/files/relay_token.txt)
./scripts/send-test.sh -t "标题" -b "正文" -l warn -s manual-test   # 健康检查+投递+回查
./scripts/send-test.sh -w                                          # 额外挂 WebSocket 看实时推送
node scripts/watch.js https://your-domain.com/hookbox "$RELAY_TOKEN"     # 只监听实时推送
```

### 3.4 普通环境（非 Muse 沙箱）的 curl

在用户自己的机器/服务器上没有打码问题，可直接：

```bash
curl -X POST "https://your-domain.com/hookbox/hook/manual-test?token=<RELAY_TOKEN>" \
  -H 'content-type: application/json' \
  -d '{"title":"测试消息","body":"如果你看到这条，链路是通的","level":"info"}'
```

---

## 4. 验证清单（发完必须验，不要只看 POST 返回）

| 检查 | 方法 | 通过标准 |
|---|---|---|
| 中转活着 | `GET /healthz` | 200 `ok` |
| 投递被接受 | POST 响应 | `ok:true` |
| 不是判重吞掉 | POST 响应 | `duplicate:false`（为 true 说明没产生新消息，见第 5 节） |
| 已落库 | `GET /api/messages?limit=1` | 最新一条就是刚发的，字段对得上 |
| raw 已透传 | 同上 | 该条带 `raw` 且内容含你附加的字段（服务端 <0.2.0 时无此字段，需先升级服务端） |
| 实时推送通 | `scripts/watch.js` 挂监听后再发一条 | 监听端秒级打印出该消息 |

向用户报告时给结论即可：标题、消息 id、以上哪几项验过。**不要**把回查到的完整 JSON 原样贴回聊天（可能含敏感 payload），摘要即可。

---

## 5. 幂等与判重（最常见的「发了但没动静」）

- 服务端按消息 id 去重：不给 `id` 时 id = 请求体 sha256。**内容一模一样的第二条会被接收但判重**，返回 `{"ok":true,"duplicate":true}`，App 不会再弹通知、列表也不会新增。
- 所以测试消息每次都要有点变化：最省事是在 body 或附加字段里带当前时间（3.3 的脚本已带 `sent_at`）。
- 平台自带投递 id 的（如 GitHub 的 `X-GitHub-Delivery`）同理，平台重投不会重复打扰用户，这是设计如此，不是故障。

---

## 6. 其他鉴权方式（一般用不到，token 足够）

优先级从高到低，服务端按此顺序验：

1. `source=github` 且服务端配了 `GITHUB_SECRET`：校验 `X-Hub-Signature-256: sha256=<HMAC-SHA256(secret, 原始body) 的十六进制>`
2. `source=gitlab` 且配了 `GENERIC_SECRET`：校验请求头 `X-Gitlab-Token` 与之相等
3. 配了 `GENERIC_SECRET` 且带 `X-HookBox-Signature` 头：值为 `HMAC-SHA256(GENERIC_SECRET, 原始body)` 的十六进制（可带 `sha256=` 前缀）
4. 兜底：URL 参数 `?token=<RELAY_TOKEN>`（本文档主用方式）

签名计算示例：

```bash
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$GENERIC_SECRET" | awk '{print $2}')
curl -X POST "https://your-domain.com/hookbox/hook/mytool" \
  -H 'content-type: application/json' -H "X-HookBox-Signature: $SIG" -d "$BODY"
```

---

## 7. 错误对照表

| 现象 | 含义 | 处理 |
|---|---|---|
| 401 `{"error":"unauthorized"}` | token 不对（或被打码成了占位符） | 先按 2.2 排查打码问题；再核对文件内容与服务端 `.env` 是否一致 |
| 401 `bad_signature` / `missing_signature` | 走了签名分支且签名不对/缺失 | 检查用的是哪个 secret、签名原文是否为请求体原文 |
| 401 `bad_token` | GitLab 分支 token 头不对 | 核对 `X-Gitlab-Token` 与 `GENERIC_SECRET` |
| 413 | 请求体 > 1 MB | 精简 payload |
| 200 但 `duplicate:true` | 判重，未产生新消息 | 改内容或加时间戳后重发（第 5 节） |
| curl `Empty reply from server` / HTTP 000 | 大概率是 token 在传输前被打码替换（见 2.2），也可能是服务端进程异常 | 先用不带 token 的 `/healthz` 与错 token 请求对照：前者 200、后者规整 401，则是打码问题 |
| 500 `store_failed` | 服务端写库失败 | 通知用户查服务器（卷权限/磁盘），AI 不要反复重试 |
| POST 成功但手机没弹 | App 侧问题（未连接/被系统杀后台/通知权限） | 引导用户看 App 顶部状态与保活设置；服务端侧可用第 4 节清单自证清白 |

---

## 8. AI 行为规范（发送前后）

- 发送前：除非用户明确要求，只发送用户给出/确认过的标题与正文，不自行编造业务内容；级别按用户语义选，拿不准用 `info`。
- 发送后：按第 4 节验证，并向用户报告标题、id 与验证结论。
- 任何时候：不输出 token；不在记忆、文档、代码仓库里写入 token 原文；不把消息历史整段回贴给用户。
- 批量发送：逐条间隔发送并逐条看返回，不要并发猛刷（服务端无速率限制，刷爆的是用户的手机通知）。
- 本文档如与服务端代码行为冲突，以仓库 `server/`（线上）代码为准，并请顺手修订本文档。
