#!/usr/bin/env bash
# 在本地起一套完全隔离的 HookBox 中转层，用来做全链路测试
# 不连线上、不碰服务器、不需要 Docker
#
# 用法：
#   ./local-dev.sh              启动（默认 127.0.0.1:18080）
#   ./local-dev.sh -p 19000     换端口
#   ./local-dev.sh -k mytoken   指定 token（默认随机生成）
#   ./local-dev.sh -c           只编译不启动
#
# 启动后另开一个终端发消息：
#   RELAY_TOKEN=<打印出来的 token> ./send-test.sh -u http://127.0.0.1:18080
#
# Ctrl+C 退出，数据留在 server/local-data/hookbox.db（下次启动还在）

set -uo pipefail

PORT=18080
TOKEN=""
BUILD_ONLY=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="$ROOT/server"
DATA_DIR="$SERVER_DIR/local-data"

while getopts "p:k:ch" opt; do
  case "$opt" in
    p) PORT="$OPTARG" ;;
    k) TOKEN="$OPTARG" ;;
    c) BUILD_ONLY=1 ;;
    h) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
    *) echo "未知参数，用 -h 看帮助" >&2; exit 2 ;;
  esac
done

command -v go >/dev/null 2>&1 || { echo "❌ 没找到 go，先装 Go 1.22+"; exit 1; }

cd "$SERVER_DIR" || exit 1

# 依赖没下过就先拉一次
if [ ! -f go.sum ] || [ ! -d "$(go env GOMODCACHE)/github.com/gorilla" ]; then
  echo "→ 拉取依赖（首次会慢一点）"
  GOTOOLCHAIN=local go mod download 2>&1 | tail -3
fi

echo "→ 编译"
BIN="hookbox-local"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) BIN="hookbox-local.exe" ;;
esac
GOTOOLCHAIN=local CGO_ENABLED=0 go build -o "$BIN" . || { echo "❌ 编译失败"; exit 1; }
echo "  ✅ $SERVER_DIR/$BIN"

if [ "$BUILD_ONLY" = "1" ]; then exit 0; fi

[ -n "$TOKEN" ] || TOKEN=$(openssl rand -hex 32 2>/dev/null || date +%s%N | sha256sum | cut -c1-64)

mkdir -p "$DATA_DIR"

cat <<EOF

┌────────────────────────────────────────────────────────────┐
│  本地实例即将启动（数据独立，不碰线上）                      │
├────────────────────────────────────────────────────────────┤
│  地址   http://127.0.0.1:$PORT
│  Token  $TOKEN
│  数据   $DATA_DIR/hookbox.db
└────────────────────────────────────────────────────────────┘

另开一个终端，这样发测试消息：

  cd "$ROOT"
  RELAY_TOKEN=$TOKEN ./scripts/send-test.sh -u http://127.0.0.1:$PORT

想同时看实时推送，加 -w：

  RELAY_TOKEN=$TOKEN ./scripts/send-test.sh -u http://127.0.0.1:$PORT -w

Ctrl+C 停止服务。

EOF

ADDR=":$PORT" \
DB_PATH="$DATA_DIR/hookbox.db" \
RELAY_TOKEN="$TOKEN" \
exec "./$BIN"
