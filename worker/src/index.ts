import { handleHook } from "./hook";
import { json, tokenOk } from "./util";

export { Hub } from "./hub";

export interface Env {
  DB: D1Database;
  HUB: DurableObjectNamespace;
  RELAY_TOKEN: string;
  GITHUB_SECRET?: string;
  GENERIC_SECRET?: string;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/healthz") return new Response("ok", { status: 200 });

    // App 端长连接
    if (path === "/ws") {
      if (request.headers.get("Upgrade") !== "websocket") {
        return json({ error: "expected_websocket" }, 426);
      }
      if (!tokenOk(url.searchParams.get("token"), env.RELAY_TOKEN)) {
        return json({ error: "unauthorized" }, 401);
      }
      return env.HUB.get(env.HUB.idFromName("global")).fetch(request);
    }

    // 断线重连后的补拉
    if (path === "/api/messages" && request.method === "GET") {
      if (!tokenOk(url.searchParams.get("token"), env.RELAY_TOKEN)) {
        return json({ error: "unauthorized" }, 401);
      }
      const before = Number(url.searchParams.get("before") ?? Date.now());
      const limit = Math.min(Number(url.searchParams.get("limit") ?? 50) || 50, 200);
      const { results } = await env.DB.prepare(
        `SELECT id, source, title, body, level, ts, raw
           FROM messages
          WHERE ts < ?
          ORDER BY ts DESC
          LIMIT ?`,
      )
        .bind(before, limit)
        .all();
      return json({ messages: results ?? [] });
    }

    // 平台回调入口：/hook/<source>
    if (path.startsWith("/hook/") && request.method === "POST") {
      const source = decodeURIComponent(path.slice("/hook/".length)).trim().toLowerCase();
      if (!source) return json({ error: "missing_source" }, 400);
      return handleHook(request, env, ctx, source);
    }

    return json({ error: "not_found" }, 404);
  },
};
