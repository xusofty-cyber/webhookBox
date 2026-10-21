package main

import (
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	writeWait  = 10 * time.Second
	pongWait   = 90 * time.Second
	pingPeriod = 30 * time.Second
	sendBuffer = 64
)

type client struct {
	conn *websocket.Conn
	send chan []byte
	done chan struct{}
	once sync.Once
}

func newClient(conn *websocket.Conn) *client {
	return &client{
		conn: conn,
		send: make(chan []byte, sendBuffer),
		done: make(chan struct{}),
	}
}

// close 幂等；send 永不关闭，避免广播时往已关闭的 channel 写入而 panic
func (c *client) close() {
	c.once.Do(func() { close(c.done) })
}

func (c *client) enqueue(p []byte) {
	select {
	case c.send <- p:
	case <-c.done:
	default:
		// 客户端太慢，丢这一帧，不拖累其他人
	}
}

func (c *client) readPump() {
	defer c.close()

	c.conn.SetReadLimit(4096)
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	for {
		if _, _, err := c.conn.ReadMessage(); err != nil {
			return
		}
	}
}

func (c *client) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer ticker.Stop()

	for {
		select {
		case p := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.TextMessage, p); err != nil {
				c.close()
				return
			}
		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				c.close()
				return
			}
		case <-c.done:
			return
		}
	}
}

type Hub struct {
	mu      sync.RWMutex
	clients map[*client]struct{}
}

func newHub() *Hub {
	return &Hub{clients: make(map[*client]struct{})}
}

func (h *Hub) add(c *client) {
	h.mu.Lock()
	h.clients[c] = struct{}{}
	h.mu.Unlock()
}

func (h *Hub) remove(c *client) {
	h.mu.Lock()
	delete(h.clients, c)
	h.mu.Unlock()
}

// Broadcast 返回实际投递到的连接数
func (h *Hub) Broadcast(p []byte) int {
	h.mu.RLock()
	defer h.mu.RUnlock()

	n := 0
	for c := range h.clients {
		select {
		case <-c.done:
			continue
		default:
		}
		select {
		case c.send <- p:
			n++
		case <-c.done:
		default:
		}
	}
	return n
}

func (h *Hub) Count() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients)
}
