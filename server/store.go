package main

import (
	"database/sql"
	"encoding/json"
	"fmt"

	_ "modernc.org/sqlite"
)

type Message struct {
	ID     string `json:"id"`
	Source string `json:"source"`
	Title  string `json:"title"`
	Body   string `json:"body"`
	Level  string `json:"level"`
	Ts     int64  `json:"ts"`
	// Raw 是原始请求体全文：title/body 是归一化后的预览，
	// 完整内容靠 Raw 透传到 App 详情页展示。
	Raw string `json:"raw"`
}

type Store struct {
	db *sql.DB
}

const schemaMessages = `
CREATE TABLE IF NOT EXISTS messages (
  id     TEXT PRIMARY KEY,
  source TEXT NOT NULL,
  title  TEXT NOT NULL,
  body   TEXT NOT NULL DEFAULT '',
  level  TEXT NOT NULL DEFAULT 'info',
  ts     INTEGER NOT NULL,
  raw    TEXT
)`

const schemaIndex = `CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages (ts DESC)`

func openStore(path string) (*Store, error) {
	// WAL 让读写不互相阻塞，busy_timeout 兜住偶发锁等待
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// SQLite 单写者，连接池开大只会增加 BUSY 概率
	db.SetMaxOpenConns(1)

	for _, stmt := range []string{schemaMessages, schemaIndex} {
		if _, err := db.Exec(stmt); err != nil {
			_ = db.Close()
			return nil, fmt.Errorf("建表失败: %w", err)
		}
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// Insert 返回 false 表示这条消息已经存在（平台重投）
func (s *Store) Insert(m Message, raw any) (bool, error) {
	// 优先存原始请求体全文（保住字段顺序与格式），没有才退回序列化 payload
	rawJSON := []byte(m.Raw)
	if len(rawJSON) == 0 {
		var err error
		rawJSON, err = json.Marshal(raw)
		if err != nil {
			rawJSON = []byte("null")
		}
	}
	res, err := s.db.Exec(
		`INSERT OR IGNORE INTO messages (id, source, title, body, level, ts, raw) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		m.ID, m.Source, m.Title, m.Body, m.Level, m.Ts, string(rawJSON),
	)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

func (s *Store) List(before int64, limit int) ([]Message, error) {
	rows, err := s.db.Query(
		`SELECT id, source, title, body, level, ts, COALESCE(raw, '') FROM messages WHERE ts < ? ORDER BY ts DESC LIMIT ?`,
		before, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Message, 0, limit)
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.Source, &m.Title, &m.Body, &m.Level, &m.Ts, &m.Raw); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}
