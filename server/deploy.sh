#!/bin/sh
# 本地交叉编译 + 上传 + 重启容器。
# 用法：./deploy.sh ubuntu@1.2.3.4 [/remote/dir]
set -eu

TARGET="${1:?用法: ./deploy.sh user@host [/remote/dir]}"
REMOTE_DIR="${2:-/opt/hookbox}"

echo "==> 交叉编译 linux/amd64"
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -trimpath -ldflags="-s -w" -o hookbox-linux-amd64 .

echo "==> 上传到 $TARGET:$REMOTE_DIR"
ssh "$TARGET" "mkdir -p '$REMOTE_DIR'"
scp -q hookbox-linux-amd64 Dockerfile.prebuilt docker-compose.yml "$TARGET:$REMOTE_DIR/"

echo "==> 生成 .env（首次）并重启"
ssh "$TARGET" "cd '$REMOTE_DIR'
if [ ! -f .env ]; then
  umask 077
  printf 'RELAY_TOKEN=%s\nGITHUB_SECRET=\nGENERIC_SECRET=\n' \"\$(openssl rand -hex 32)\" > .env
  echo '已生成 .env'
fi
docker compose -p hookbox up -d --build
docker compose -p hookbox ps
"

echo "==> 完成。RELAY_TOKEN 在服务器 $REMOTE_DIR/.env 里"
