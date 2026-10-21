package main

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

const maxBodyBytes = 1 << 20 // 1MB

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	// 鉴权靠 token，不靠 Origin（手机端没有浏览器 Origin）
	CheckOrigin: func(r *http.Request) bool { return true },
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func (s *server) handleWS(w http.ResponseWriter, r *http.Request) {
	if !tokenOK(r.URL.Query().Get("token"), s.cfg.RelayToken) {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	c := newClient(conn)
	s.hub.add(c)
	log.Printf("WebSocket 已连接，当前在线 %d", s.hub.Count())

	defer func() {
		s.hub.remove(c)
		c.close()
		_ = conn.Close()
		log.Printf("WebSocket 断开，当前在线 %d", s.hub.Count())
	}()

	_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
	if err := conn.WriteJSON(map[string]any{"type": "hello", "ts": time.Now().UnixMilli()}); err != nil {
		return
	}

	go c.writePump()
	c.readPump()
}

func (s *server) handleList(w http.ResponseWriter, r *http.Request) {
	if !tokenOK(r.URL.Query().Get("token"), s.cfg.RelayToken) {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}

	before := time.Now().UnixMilli()
	if v := r.URL.Query().Get("before"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			before = n
		}
	}

	limit := 50
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = min(n, 200)
		}
	}

	messages, err := s.store.List(before, limit)
	if err != nil {
		log.Printf("查询消息失败: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "query_failed"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"messages": messages})
}

func (s *server) handleHook(w http.ResponseWriter, r *http.Request) {
	source := strings.ToLower(strings.TrimSpace(r.PathValue("source")))
	if source == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "missing_source"})
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err != nil {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]any{"ok": false, "error": "body_too_large"})
		return
	}

	if err := s.verify(source, r, body); err != nil {
		log.Printf("拒绝 %s: %v", source, err)
		writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "error": err.Error()})
		return
	}

	var payload any
	if err := json.Unmarshal(body, &payload); err != nil {
		payload = map[string]any{"raw": string(body)}
	}

	msg := normalize(source, payload, body, r.Header)
	inserted, err := s.store.Insert(msg, payload)
	if err != nil {
		log.Printf("写入失败: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": "store_failed"})
		return
	}

	if inserted {
		if frame, err := json.Marshal(map[string]any{"type": "msg", "data": msg}); err == nil {
			s.hub.Broadcast(frame)
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"id":        msg.ID,
		"duplicate": !inserted,
	})
}
