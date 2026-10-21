import type { Env } from "./index";
import { hmacHex, safeEqual, tokenOk } from "./util";

export type Verdict = { ok: true } | { ok: false; reason: string };

/**
 * 验签策略，按优先级：
 * 1. github 源 + 配了 GITHUB_SECRET  -> 校验 X-Hub-Signature-256
 * 2. 配了 GENERIC_SECRET 且带了 X-HookBox-Signature -> 校验通用 HMAC
 * 3. 退化成 URL 里带 ?token=<RELAY_TOKEN>
 *
 * 注意：平台不同，签名头不同。Stripe 是 Stripe-Signature（含 t= 和 v1=，
 * 且签的是 "t.body"），GitLab 是 X-Gitlab-Token（明文比对）。用到哪个补哪个。
 */
export async function verifyHook(
  source: string,
  rawBody: string,
  request: Request,
  env: Env,
): Promise<Verdict> {
  const headers = request.headers;
  const url = new URL(request.url);

  if (source === "github" && env.GITHUB_SECRET) {
    const provided = headers.get("X-Hub-Signature-256");
    if (!provided) return { ok: false, reason: "missing_signature" };
    const expected = "sha256=" + (await hmacHex(env.GITHUB_SECRET, rawBody));
    return safeEqual(provided, expected) ? { ok: true } : { ok: false, reason: "bad_signature" };
  }

  if (source === "gitlab" && env.GENERIC_SECRET) {
    const provided = headers.get("X-Gitlab-Token");
    return safeEqual(provided, env.GENERIC_SECRET)
      ? { ok: true }
      : { ok: false, reason: "bad_token" };
  }

  if (env.GENERIC_SECRET) {
    const provided = headers.get("X-HookBox-Signature");
    if (provided) {
      const expected = await hmacHex(env.GENERIC_SECRET, rawBody);
      const normalized = provided.replace(/^sha256=/i, "").toLowerCase();
      return safeEqual(normalized, expected)
        ? { ok: true }
        : { ok: false, reason: "bad_signature" };
    }
  }

  // 兜底：把 token 拼在 URL 上。很多平台只支持填一个回调地址，这是唯一办法。
  if (tokenOk(url.searchParams.get("token"), env.RELAY_TOKEN)) return { ok: true };

  return { ok: false, reason: "unauthorized" };
}
