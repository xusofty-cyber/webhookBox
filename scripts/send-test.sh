#!/usr/bin/env bash
# HookBox 本地测试脚本 —— 往中转层发一条消息，验证链路是否通畅
#
# 用法：
#   ./send-test.sh                        发一条默认测试消息
#   ./send-test.sh -t 标题 -b 正文 -l warn
#   ./send-test.sh -s github              指定来源（默认 manual-test）
#   ./send-test.sh -w                     先挂 WebSocket 监听，再发消息
#   ./send-test.sh -n 5                   连发 5 条（带序号，绕过幂等）
#   ./send-test.sh -H                     只做健康检查
#   ./send-test.sh -h                     看帮助
#
# 地址与 token 的来源优先级：命令行 -u/-k > 环境变量 > server/.env

set -uo pipefail

BASE="${HOOKBOX_URL:-}"
TOKEN="${RELAY_TOKEN:-}"

# 从 server/.env 兜底读取（脚本在 scripts/ 下，往上一级找）
ENV_FILE="$(cd "$(dirname "$0")/.." && pwd)/server/.env"
if [ -z "$BASE" ] && [ -f "$ENV_FILE" ]; then
  BASE=$(grep -E '^HOOKBOX_URL=' "$ENV_FILE" 2>/dev/null | cut -d= -f2-)
fi
if [ -z "$TOKEN" ] && [ -f "$ENV_FILE" ]; then
  TOKEN=$(grep -E '^RELAY_TOKEN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2-)
fi

# 本地 .env 里通常没有 HOOKBOX_URL，用线上地址兜底
BASE="${BASE:-https://your-domain.com/hookbox}"
BASE="${BASE%/}"

TITLE="本地测试"
BODY="链路测试 $(date '+%H:%M:%S')"
LEVEL="info"
SOURCE="manual-test"
WATCH=0
ONLY_HEALTH=0
COUNT=1

while getopts "t:b:l:s:u:k:n:whH" opt; do
  case "$opt" in
    t) TITLE="$OPTARG" ;;
    b) BODY="$OPTARG" ;;
    l) LEVEL="$OPTARG" ;;
    s) SOURCE="$OPTARG" ;;
    u) BASE="${OPTARG%/}" ;;
    k) TOKEN="$OPTARG" ;;
    n) COUNT="$OPTARG" ;;
    w) WATCH=1 ;;
    H) ONLY_HEALTH=1 ;;
    h) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
    *) echo "未知参数，用 -h 看帮助" >&2; exit 2 ;;
  esac
done

if [ -z "$TOKEN" ]; then
  echo "❌ 没有 token。三种给法："
  echo "   1) export RELAY_TOKEN=xxx"
  echo "   2) 在 server/.env 里写 RELAY_TOKEN=xxx"
  echo "   3) ./send-test.sh -k xxx"
  exit 1
fi

echo "→ 目标 $BASE"
echo "→ token ${TOKEN:0:8}…${TOKEN: -4}（已隐藏中段）"
echo

# ---------- 健康检查 ----------
echo "【健康检查】"
HC=$(curl -s -o /tmp/hb_health.$$ -w "%{http_code}" --max-time 12 "$BASE/healthz")
if [ "$HC" = "200" ]; then
  echo "  ✅ 200  $(cat /tmp/hb_health.$$)"
else
  echo "  ❌ HTTP $HC —— 服务没通，先查容器：docker compose -p hookbox ps"
  rm -f /tmp/hb_health.$$
  exit 1
fi
rm -f /tmp/hb_health.$$
[ "$ONLY_HEALTH" = "1" ] && exit 0
echo

# ---------- 可选：先挂 WebSocket 实时监听 ----------
WS_PID=""
if [ "$WATCH" = "1" ]; then
  echo "【WebSocket 实时监听】Ctrl+C 结束"
  WATCHER="$(cd "$(dirname "$0")" && pwd)/watch.js"
  if command -v node >/dev/null 2>&1; then
    node "$WATCHER" "$BASE" "$TOKEN" &
    WS_PID=$!
  else
    echo "  ⚠️  未找到 node，跳过实时监听（直接看下面的落库回查）"
  fi
  sleep 2
fi

# ---------- 发消息 ----------
echo
echo "【投递】"
FAIL=0
for i in $(seq 1 "$COUNT"); do
  # 每条都带唯一后缀，否则会被幂等去重（这是预期行为）
  STAMP=$(date '+%H:%M:%S').$i
  PAYLOAD=$(printf '{"title":"%s","body":"%s","level":"%s"}' \
    "$TITLE" "$BODY [$STAMP]" "$LEVEL")
  RESP=$(curl -s -w "\n%{http_code}" --max-time 12 -X POST \
    "$BASE/hook/$SOURCE?token=$TOKEN" \
    -H 'content-type: application/json' \
    -d "$PAYLOAD")
  CODE=$(echo "$RESP" | tail -1)
  BODYJSON=$(echo "$RESP" | sed '$d')
  if [ "$CODE" = "200" ]; then
    if echo "$BODYJSON" | grep -q '"duplicate":true'; then
      echo "  ⚠️  [$i] 200 重复消息（同样内容已发过，被幂等拦截）"
    else
      echo "  ✅ [$i] 200 $BODYJSON"
    fi
  else
    echo "  ❌ [$i] HTTP $CODE $BODYJSON"
    FAIL=$((FAIL+1))
  fi
  [ "$i" -lt "$COUNT" ] && sleep 0.4
done

# ---------- 回查历史 ----------
echo
echo "【落库回查】最近 3 条"
curl -s --max-time 12 "$BASE/api/messages?token=$TOKEN&limit=3" | python -c "
import sys, json, datetime
try:
    d = json.load(sys.stdin)
except Exception:
    print('  解析失败，原始响应：', sys.stdin.read()[:200]); sys.exit()
for m in d.get('messages', []):
    ts = datetime.datetime.fromtimestamp(m['ts']/1000).strftime('%H:%M:%S')
    print(f\"  {ts}  [{m.get('level','info'):5s}] {m.get('source','?'):12s} {m.get('title','')}\")
" 2>/dev/null || echo "  （回查失败，不影响投递结论）"

if [ -n "$WS_PID" ]; then
  echo
  echo "等 3 秒收完实时推送…"
  sleep 3
  kill "$WS_PID" 2>/dev/null
  wait "$WS_PID" 2>/dev/null
fi

echo
[ "$FAIL" = "0" ] && echo "结论：中转层正常。若手机没收到 → 问题在 App 侧（服务未开 / 未连上 / 被系统杀）。" \
                  || echo "结论：有 $FAIL 条投递失败，看上面的 HTTP 码。"
exit "$FAIL"
