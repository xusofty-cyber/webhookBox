package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

type Config struct {
	Addr          string
	DBPath        string
	RelayToken    string
	GitHubSecret  string
	GenericSecret string
}

func loadConfig() (Config, error) {
	cfg := Config{
		Addr:          env("ADDR", ":8080"),
		DBPath:        env("DB_PATH", "/data/hookbox.db"),
		RelayToken:    os.Getenv("RELAY_TOKEN"),
		GitHubSecret:  os.Getenv("GITHUB_SECRET"),
		GenericSecret: os.Getenv("GENERIC_SECRET"),
	}
	if len(cfg.RelayToken) < 16 {
		return cfg, errors.New("RELAY_TOKEN 没设或太短（至少 16 位，建议 openssl rand -hex 32）")
	}
	return cfg, nil
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

type server struct {
	cfg   Config
	store *Store
	hub   *Hub
}

func main() {
	log.SetFlags(log.LstdFlags | log.Lmsgprefix)
	log.SetPrefix("[hookbox] ")

	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}

	store, err := openStore(cfg.DBPath)
	if err != nil {
		log.Fatalf("打开数据库失败: %v", err)
	}
	defer store.Close()

	s := &server{cfg: cfg, store: store, hub: newHub()}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("GET /ws", s.handleWS)
	mux.HandleFunc("GET /api/messages", s.handleList)
	mux.HandleFunc("POST /hook/{source}", s.handleHook)

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           withLogging(mux),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		// WebSocket 是长连接，写超时必须关掉
		WriteTimeout: 0,
		IdleTimeout:  120 * time.Second,
	}

	go func() {
		log.Printf("监听 %s，数据库 %s", cfg.Addr, cfg.DBPath)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("启动失败: %v", err)
		}
	}()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()

	log.Println("收到退出信号，正在关闭…")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// WebSocket 升级会 hijack 连接，跳过日志包装以免破坏 Upgrade
		if r.Header.Get("Upgrade") == "websocket" {
			next.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, rec.status, time.Since(start).Round(time.Millisecond))
	})
}
