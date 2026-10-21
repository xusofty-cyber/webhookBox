import { clamp, sha256 } from "./util";

export interface RelayMessage {
  id: string;
  source: string;
  title: string;
  body: string;
  level: "info" | "warn" | "error";
  ts: number;
  /** 原始请求体全文。归一化后的 title/body 是给列表预览用的，
   *  完整内容靠 raw 透传到 App 详情页，不能在这里截断丢掉。 */
  raw: string;
}

/** 正文上限：只防极端大 payload 撑爆广播帧，正常 webhook 远达不到 */
const BODY_MAX = 20_000;
const TITLE_MAX = 300;

type AnyPayload = Record<string, any>;

/**
 * 把各平台千奇百怪的 payload 压成统一的 {title, body, level}。
 * 没覆盖到的平台会自动走通用分支，取 title/subject/message 之类的常见字段。
 */
export async function normalize(
  source: string,
  payload: unknown,
  rawBody: string,
  headers: Headers,
): Promise<RelayMessage> {
  const p = (payload ?? {}) as AnyPayload;
  const ts = Date.now();

  if (source === "github") {
    const event = headers.get("X-GitHub-Event") ?? "event";
    const id = headers.get("X-GitHub-Delivery") ?? `github:${await sha256(rawBody)}`;
    const repo = p.repository?.full_name ?? "";
    let body = "";
    let level: RelayMessage["level"] = "info";

    switch (event) {
      case "push": {
        const commit = p.head_commit;
        body = commit?.message ?? `${(p.commits ?? []).length} commits`;
        break;
      }
      case "pull_request":
        body = `${p.action}: ${p.pull_request?.title ?? ""}`;
        break;
      case "issues":
        body = `${p.action}: ${p.issue?.title ?? ""}`;
        break;
      case "workflow_run": {
        const run = p.workflow_run;
        body = `${run?.name ?? "workflow"} ${p.action} / ${run?.conclusion ?? "running"}`;
        if (p.action === "completed" && run?.conclusion === "failure") level = "error";
        break;
      }
      case "release":
        body = `${p.action}: ${p.release?.tag_name ?? ""}`;
        break;
      default:
        body = p.action ?? JSON.stringify(p);
    }

    return {
      id,
      source: "github",
      title: clamp(repo ? `GitHub · ${repo}` : `GitHub · ${event}`, TITLE_MAX),
      body: clamp(body, BODY_MAX),
      level,
      ts,
      raw: rawBody,
    };
  }

  const id = p.id ? `${source}:${p.id}` : `${source}:${await sha256(rawBody)}`;
  const rawLevel = String(p.level ?? p.severity ?? "info").toLowerCase();
  const level: RelayMessage["level"] =
    rawLevel === "error" || rawLevel === "critical" || rawLevel === "fatal"
      ? "error"
      : rawLevel === "warn" || rawLevel === "warning"
        ? "warn"
        : "info";

  return {
    id,
    source,
    title: clamp(p.title ?? p.subject ?? p.name ?? `${source} 通知`, TITLE_MAX),
    body: clamp(p.body ?? p.text ?? p.message ?? p.content ?? "", BODY_MAX),
    level,
    ts: Number(p.ts ?? p.timestamp ?? ts) || ts,
    raw: rawBody,
  };
}
