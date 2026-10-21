-- HookBox 中转层数据结构
-- 用法：wrangler d1 execute hookbox --remote --file=schema.sql

CREATE TABLE IF NOT EXISTS messages (
  id      TEXT PRIMARY KEY,   -- 去重键：平台事件 id，或 body 的 sha256
  source  TEXT NOT NULL,      -- github / stripe / uptime ...
  title   TEXT NOT NULL,
  body    TEXT NOT NULL DEFAULT '',
  level   TEXT NOT NULL DEFAULT 'info',   -- info | warn | error
  ts      INTEGER NOT NULL,   -- 毫秒时间戳
  raw     TEXT                -- 原始 payload，便于排查
);

CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages (ts DESC);
CREATE INDEX IF NOT EXISTS idx_messages_source ON messages (source, ts DESC);
