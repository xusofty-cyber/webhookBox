const ws = new WebSocket("ws://127.0.0.1:8787/ws?token=testtoken");

ws.onopen = () => console.log("OPEN");
ws.onmessage = (e) => console.log("RECV", e.data);
ws.onerror = (e) => console.log("ERR", e.message ?? String(e));

setTimeout(async () => {
  const res = await fetch("http://127.0.0.1:8787/hook/wstest?token=testtoken", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ title: "来自 WS 的推送", body: "hello world", level: "warn" }),
  });
  console.log("POST", res.status, await res.text());
}, 700);

setTimeout(() => {
  ws.close();
  process.exit(0);
}, 3000);
