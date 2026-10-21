# HookBox Server

Worker 版中转层的自托管替代品。**协议完全一致**——同一个安卓 App，把地址从 `*.workers.dev` 换成自己的域名就能用。

Go 单二进制 + SQLite，常驻内存约 2.5MB。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/hook/{source}` | 收 webhook，`{source}` 只是标签 |
| GET | `/ws?token=` | 安卓端长连接 |
| GET | `/api/messages?token=&before=&limit=` | 断线补拉 |
| GET | `/healthz` | 健康检查 |

验签优先级与 Worker 版一致：GitHub `X-Hub-Signature-256` → GitLab `X-Gitlab-Token` → 通用 `X-HookBox-Signature` → URL 上带 `?token=`。

## 构建

程序是静态编译的，所以有两种打法。

**A. 本地交叉编译 + 上传（推荐，服务器不需要 Go）**

```bash
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -trimpath -ldflags="-s -w" -o hookbox-linux-amd64 .

scp hookbox-linux-amd64 Dockerfile.prebuilt docker-compose.yml user@host:~/projects/hookbox/
```

**B. 在服务器上从源码构建**

把 `docker-compose.yml` 里的 `dockerfile: Dockerfile.prebuilt` 改成 `dockerfile: Dockerfile`，然后 `docker compose up -d --build`。

> ⚠️ 编译 modernc.org/sqlite 很吃内存，2GB 以下的机器大概率 OOM。这就是默认走 A 的原因。

## 部署

```bash
cd ~/projects/hookbox

# 生成 .env（只做一次）
umask 077
printf 'RELAY_TOKEN=%s\nGITHUB_SECRET=\nGENERIC_SECRET=\n' "$(openssl rand -hex 32)" > .env

docker compose -p hookbox up -d --build
docker compose -p hookbox ps
docker logs -f hookbox-relay
```

`-p hookbox` 显式指定 compose 项目名，保证和机器上其他项目完全隔离。

容器只监听 `127.0.0.1:18080`，不直接对外。对外由宿主机 nginx 终结 TLS。

## nginx 反代

见 `nginx-example.conf`。核心就三点：路径前缀转发、WebSocket 的 Upgrade 头、长 timeout。

```nginx
location /hookbox/ {
    proxy_pass http://127.0.0.1:18080/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_buffering off;
    proxy_read_timeout 3600s;
}
```

`proxy_pass` 结尾那个 `/` 不能少，它负责把 `/hookbox/` 前缀剥掉。

## 回滚

```bash
sudo rm /etc/nginx/sites-enabled/your-domain.com /etc/nginx/sites-available/your-domain.com
sudo nginx -t && sudo systemctl reload nginx

docker compose -p hookbox down          # 停服务
docker volume rm hookbox_hookbox-data   # 连数据一起删（可选）
```

nginx 配置的完整备份在 `<nginx配置备份目录>`。

## 运维

```bash
docker logs --tail 100 hookbox-relay          # 看日志
docker stats --no-stream hookbox-relay        # 看资源
docker compose -p hookbox restart             # 重启

# 备份数据库
docker run --rm -v hookbox_hookbox-data:/d -v "$PWD":/out alpine \
  tar czf /out/hookbox-backup-$(date +%F).tgz -C /d .
```

## 换 RELAY_TOKEN

```bash
cd ~/projects/hookbox
sed -i "s/^RELAY_TOKEN=.*/RELAY_TOKEN=$(openssl rand -hex 32)/" .env
docker compose -p hookbox up -d
```

改完 App 里的 token 和所有平台回调 URL 里的 `?token=` 也要跟着换。

## 和 Worker 版的差异

| | Worker 版 | 自托管版 |
|---|---|---|
| 冷启动 | 无 | 无（常驻进程） |
| 存储 | D1 | SQLite（单文件） |
| 长连接 | Durable Object | 进程内 Hub |
| 多设备 | 支持 | 支持 |
| 多实例横向扩展 | 天然支持 | 不支持（单实例，Hub 在内存里） |
| 成本 | 免费额度 | 服务器本身 |
| 运维 | 零 | 自己管 |
