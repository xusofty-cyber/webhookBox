#!/usr/bin/env node
// 监听 HookBox 的 WebSocket，实时打印推送过来的消息
// 用法：node watch.js [地址] [token]
//   地址可省略，默认 https://your-domain.com/hookbox
// 需要 Node 22+（自带全局 WebSocket，零依赖）

const BASE = (process.argv[2] || process.env.HOOKBOX_URL || "https://your-domain.com/hookbox").replace(/\/+$/, "");
const TOKEN = process.argv[3] || process.env.RELAY_TOKEN || "";

if (typeof WebSocket === "undefined") {
  console.error(`❌ 当前 Node ${process.version} 没有内置 WebSocket，需要 22 或更高`);
  process.exit(1);
}
if (!TOKEN) {
  console.error("❌ 缺少 token：node watch.js <地址> <token>");
  process.exit(1);
}

const wsUrl = BASE.replace(/^http/, "ws") + "/ws?token=" + encodeURIComponent(TOKEN);
console.log(`→ 连接 ${wsUrl.replace(TOKEN, TOKEN.slice(0, 8) + "…")}`);

let retry = 0;
function connect() {
  const ws = new WebSocket(wsUrl);

  ws.addEventListener("open", () => {
    retry = 0;
    console.log("✅ 已连接，等待推送（Ctrl+C 退出）\n");
  });

  ws.addEventListener("message", (ev) => {
    const now = new Date().toTimeString().slice(0, 8);
    let d;
    try {
      d = JSON.parse(ev.data);
    } catch {
      console.log(`${now}  ${ev.data}`);
      return;
    }
    // 服务端帧格式：{"type":"hello"} / {"type":"msg","data":{...}}
    if (d.type === "hello") {
      console.log(`${now}  · 握手成功`);
      return;
    }
    if (d.type !== "msg") {
      console.log(`${now}  · ${d.type || "未知帧"}`);
      return;
    }
    const m = d.data || d;
    const level = (m.level || "info").padEnd(5);
    console.log(`${now}  [${level}] ${m.source || "?"} → ${m.title || ""}`);
    if (m.body) console.log(`         ${m.body}`);
  });

  ws.addEventListener("error", () => {
    console.error("连接出错（地址/token 是否正确？）");
  });

  ws.addEventListener("close", () => {
    const wait = Math.min(1000 * 2 ** retry++, 30000);
    console.log(`\n断开，${wait / 1000}s 后重连…`);
    setTimeout(connect, wait);
  });
}

connect();
process.on("SIGINT", () => {
  console.log("\n已退出");
  process.exit(0);
});
