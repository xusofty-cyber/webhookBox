# HookBox

把任意平台的 webhook 收到安卓手机上。自建中转，不依赖第三方推送服务，也不依赖 GMS。

**中转层有两套实现，协议完全一致，安卓端不用改：**

| 实现 | 适合 | 入口 |
|---|---|---|
| Cloudflare Worker | 不想运维、要弹性扩容 | [`worker/`](worker/) |
| Go + Docker 自托管 | 已有服务器、数据要自己攥着 | [`server/`](server/) |

📖 **完整文档见 [`docs/`](docs/)**：部署文档（含本次实际部署记录与回滚方法）、使用说明（App 用法、各平台接入、故障排查）。

```
平台 (GitHub / Stripe / 监控告警)
        │  POST /hook/<source>
        ▼
中转层（Worker 或 Go 容器）──► 落库 + 幂等去重
        │
        │  实时广播
        ▼
长连接中枢（Durable Object / 进程内 Hub）
        │  WSS
        ▼
安卓 App（前台服务 + Room 本地库 + 通知）
```

## 为什么不是手机直接收

手机在运营商 NAT 后面，没有公网 IP，外部请求进不来；即使打洞成功，Wi-Fi/4G 切换会换 IP，Doze 会回收进程。所以公网入口必须落在别处，手机只负责"被叫醒"。这里选了 Cloudflare Workers：免费额度每天 10 万次请求，自带 HTTPS 和域名，Durable Object 空闲休眠不计费。

## 一、部署中转层

```bash
cd worker
npm install

# 1. 建 D1 库，把输出的 database_id 填进 wrangler.toml
npx wrangler d1 create hookbox

# 2. 建表
npm run db:init

# 3. 生成一个足够长的随机 token（App 端要用同一个）
openssl rand -hex 32

# 4. 写入 secrets
npx wrangler secret put RELAY_TOKEN        # 上面生成的随机串
npx wrangler secret put GITHUB_SECRET      # GitHub webhook 的 secret，可选
npx wrangler secret put GENERIC_SECRET     # 其他平台的通用 HMAC 密钥，可选

# 5. 上线
npm run deploy
```

部署完会得到一个 `https://hookbox-relay.<你的子域>.workers.dev`。

## 二、在平台上配 webhook

| 平台 | 回调地址 | 签名方式 |
|---|---|---|
| GitHub | `https://…/hook/github` | 自动校验 `X-Hub-Signature-256`，secret 填 `GITHUB_SECRET` |
| GitLab | `https://…/hook/gitlab?token=<RELAY_TOKEN>` | 校验 `X-Gitlab-Token`，值填 `GENERIC_SECRET` |
| 其他平台 | `https://…/hook/<任意名字>?token=<RELAY_TOKEN>` | 支持 `X-HookBox-Signature`（HMAC-SHA256 十六进制）或 URL token |

`<source>` 只是个标签，随便起，App 里会显示成消息来源。

通用平台的 payload 会自动抓 `title` / `body` / `message` / `level` 这几个常见字段；不够用就改 `worker/src/normalize.ts`。

### 本地先测一发

```bash
cd worker && npm install
npm run db:init:local          # 建本地 sqlite
npm run dev:test               # 起在 8787，token 固定为 testtoken

# 另开一个终端，发一条
curl -X POST "http://127.0.0.1:8787/hook/test?token=testtoken" \
  -H 'content-type: application/json' \
  -d '{"title":"部署完成","body":"api 已上线 v1.2.0","level":"info"}'
# → {"ok":true,"id":"test:...","duplicate":false}

# 再发一次同样的内容，验证幂等
# → {"ok":true,"id":"test:...","duplicate":true}
```

两个冒烟脚本，跑起来会连真实服务：

```bash
npm run test:ws    # 建 WebSocket，发一条 webhook，看是否实时收到
npm run test:gh    # 校验 GitHub HMAC：合法 / 错误签名 / 缺签名头
```

实测输出：

```
OPEN
RECV {"type":"hello","ts":...}
RECV {"type":"msg","data":{"source":"wstest","title":"来自 WS 的推送",...}}
POST 200 {"ok":true,"duplicate":false}

合法签名   200 {"ok":true,"id":"delivery-abc-123"}
错误签名   401 {"ok":false,"error":"bad_signature"}
没有签名头 401 {"ok":false,"error":"missing_signature"}
```

### 往已部署的服务发测试消息

`scripts/` 下有四份工具，不依赖任何第三方包：

```bash
export RELAY_TOKEN=<你的token>
./scripts/send-test.sh              # 健康检查 + 投递 + 落库回查
./scripts/send-test.sh -w           # 顺带挂 WebSocket，看实时推送
node scripts/watch.js <地址> <token> # 只监听，不发
./scripts/local-dev.sh              # 本地起隔离实例（需 Go）
```

Windows 原生 PowerShell 用 `.\scripts\send-test.ps1`，装了 VS Code REST Client 插件可以用 `scripts/test.http` 点着发。

细节和返回码对照见 [`scripts/README.md`](scripts/README.md)。


## 三、构建 App

用 Android Studio 打开 `android/` 目录，等 Gradle 同步完直接跑。

debug 构建放开了明文 HTTP，可以直接连电脑上的 `http://<你的局域网IP>:8787` 调试；release 只允许 `wss://`。

首次启动后：点右上角齿轮进「设置」填入 Worker 地址和 `RELAY_TOKEN` → 保存并连接 → 允许通知 → 在设置页把 App 加进电池优化白名单。

## 四、保活清单（国内 ROM 必做）

不同厂商路径不同，但基本都是这几项：

- **电池优化**：设置 → 电池 → 不受限制 / 允许后台活动
- **自启动**：应用管理 → 自启动管理 → 允许（小米/OPPO/vivo 都有）
- **后台运行**：华为「应用启动管理」改成手动管理，三个开关全开
- **最近任务加锁**：在多任务界面下拉锁定，防止一键清理带走

即使全做了，也可能偶尔被杀。App 的重连是指数退避 + 抖动，最长 60 秒重试一次，每次（重）连成功后自动靠 `/api/messages` 补拉最近 200 条历史（按 id 去重，补拉不弹通知）。

## 五、几个设计取舍

**为什么用 `specialUse` 而不是 `dataSync` 前台服务**
Android 15 起 `dataSync` 类型每 24 小时只能用 6 小时，长连接会被系统掐断。`specialUse` 没有时长限制，但上架 Google Play 需要写用途说明（本项目按自用/侧载设计）。

**为什么用 Durable Object 而不是自己维护连接表**
Worker 是无状态的，每次请求可能在新的 isolate 里跑，内存里的 WebSocket 集合根本活不过一次请求。DO 是唯一能持有长连接的地方，而且用 hibernation API 后空闲时不烧 CPU。

**为什么消息要落 D1**
两个作用：幂等去重（平台超时重投很常见，同一个 `X-GitHub-Delivery` 只存一次），以及手机断线期间的补拉。

**为什么返回 200 要快**
GitHub 等平台 10 秒超时就重投，重投又触发新一轮。所以写库后立刻返回，广播丢给 `ctx.waitUntil` 异步做。

## 六、已知限制

- 同一时刻只支持一个全局 Hub，多设备同时在线是可以的（都挂在同一个 DO 上），但没有按设备分组
- 没有做消息保留策略，D1 会一直长。生产环境建议加个定时任务删 30 天前的
- 断线补拉每次最多拉最近 200 条，离线更久之前的消息不会自动补（可在服务端 `/api/messages` 手动查）
- 签名只内置了 GitHub / GitLab / 通用 HMAC 三种，Stripe 的 `t=...,v1=...` 格式需要自己补

## 目录

```
docs/              部署文档、使用说明（存档用，服务器上也有一份）
scripts/           测试工具
  send-test.sh       发测试消息（健康检查 + 投递 + 回查）
  send-test.ps1      同上，Windows 原生 PowerShell
  watch.js           单独监听 WebSocket
  test.http          REST Client 请求集合
  local-dev.sh       本地起隔离实例
worker/            Cloudflare Worker 中转层（TypeScript）
  src/index.ts      路由
  src/hook.ts       收 webhook 主流程
  src/verify.ts     验签
  src/normalize.ts  各平台 payload → 统一消息
  src/hub.ts        Durable Object WebSocket 中枢
  test/             两个冒烟脚本
  schema.sql        D1 表结构
server/            Go 自托管中转层
  main.go           配置 + 启动 + 优雅退出
  web.go            HTTP 处理器
  verify.go         验签
  normalize.go      各平台 payload → 统一消息
  hub.go            WebSocket Hub
  store.go          SQLite
  Dockerfile        从源码构建
  Dockerfile.prebuilt  直接塞二进制（服务器内存小时用这个）
  docker-compose.yml
  nginx-example.conf
  deploy.sh
android/
  app/src/main/java/com/hookbox/app/
    data/           Room + DataStore
    net/            WebSocket 重连
    service/        前台服务 + 开机自启
    ui/             Compose 界面
```

## 验证状态

**两套中转层都已跑通，返回的 id、字段、状态码完全一致**（同一份 body 算出的 sha256 相同，可以直接互换）。

Worker 版（wrangler 本地模拟，D1 + DO 都真实）：

- `tsc --noEmit` 零错误
- 投递 200 / 重投 `duplicate:true` / 错 token 401
- WebSocket 收到 `hello` 并实时收到推送
- GitHub HMAC：合法 200 / 错签 401 / 缺头 401
- `workflow_run` 失败自动 `level:error`

Go 版（本地 + 线上服务器都验过）：

- `go build` 通过，9.5MB 静态二进制
- 同样的五组接口测试全部一致
- 线上：`https://your-domain.com/hookbox/healthz` → `ok`
- 线上 WSS：`wss://your-domain.com/hookbox/ws` 连上并实时收到推送
- 常驻内存 2.5MB

**Android 层未编译验证**。这台机器没有 Android SDK，JDK 也只有 8（AGP 8.7 需要 17），所以客户端代码是逐文件核对写的，没有跑过 `assembleDebug`。第一次在 Android Studio 里同步时如果报错，大概率集中在依赖版本号上，按提示升一下即可，业务代码不依赖特定版本。

