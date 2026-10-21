import type { Env } from "./index";
import { normalize } from "./normalize";
import { json } from "./util";
import { verifyHook } from "./verify";

/**
 * 收 webhook 的主流程：验签 -> 归一化 -> 幂等落库 -> 广播。
 *
 * 全程只在 D1 写一行，不做重活，保证 200 尽快返回。
 * 平台侧一般 5~10 秒超时就重投，所以"快"比"做完"重要，
 * 真正的分发交给 Durable Object 异步完成。
 */
export async function handleHook(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  source: string,
): Promise<Response> {
  const rawBody = await request.text();

  const verdict = await verifyHook(source, rawBody, request, env);
  if (!verdict.ok) return json({ ok: false, error: verdict.reason }, 401);

  let payload: unknown;
  try {
    payload = JSON.parse(rawBody);
  } catch {
    payload = { raw: rawBody };
  }

  const msg = await normalize(source, payload, rawBody, request.headers);

  const result = await env.DB.prepare(
    `INSERT OR IGNORE INTO messages (id, source, title, body, level, ts, raw)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
  )
    .bind(msg.id, msg.source, msg.title, msg.body, msg.level, msg.ts, JSON.stringify(payload))
    .run();

  const duplicate = (result.meta.changes ?? 0) === 0;

  if (!duplicate) {
    ctx.waitUntil(
      env.HUB.get(env.HUB.idFromName("global"))
        .fetch("https://hub/broadcast", {
          method: "POST",
          body: JSON.stringify(msg),
        })
        .catch(() => undefined),
    );
  }

  return json({ ok: true, id: msg.id, duplicate });
}
