import { json } from "./util";

/**
 * 全局唯一的 WebSocket 中枢。
 *
 * 用 hibernation API（state.acceptWebSocket）而不是自己维护 Set<WebSocket>：
 * 空闲时这个 Durable Object 会被休眠，内存里的连接句柄由运行时保管，
 * 有消息进来时再唤醒。这样常驻长连接几乎不产生 CPU 计费。
 */
export class Hub implements DurableObject {
  constructor(private readonly state: DurableObjectState) {}

  async fetch(request: Request): Promise<Response> {
    const upgrade = request.headers.get("Upgrade");

    if (upgrade === "websocket") {
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair) as [WebSocket, WebSocket];
      this.state.acceptWebSocket(server);
      server.send(JSON.stringify({ type: "hello", ts: Date.now() }));
      return new Response(null, { status: 101, webSocket: client });
    }

    if (request.method === "POST" && new URL(request.url).pathname === "/broadcast") {
      const message = await request.text();
      const frame = JSON.stringify({ type: "msg", data: JSON.parse(message) });
      let delivered = 0;
      for (const ws of this.state.getWebSockets()) {
        try {
          ws.send(frame);
          delivered++;
        } catch {
          // 连接已死，运行时会在下一次事件里把它清掉
        }
      }
      return json({ delivered });
    }

    return json({ error: "bad_request" }, 400);
  }

  /** 客户端心跳，顺便刷新 DO 的活跃状态 */
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (message === "ping") ws.send("pong");
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    try {
      ws.close(code === 1006 ? 1000 : code, reason);
    } catch {
      /* 已经关了 */
    }
  }
}
