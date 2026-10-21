package main

import (
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// normalize 把各平台 payload 压成统一的 {title, body, level}。
// 字段抽取逻辑与 worker/src/normalize.ts 一一对应，换后端不影响 App。
func normalize(source string, payload any, body []byte, h http.Header) Message {
	now := time.Now().UnixMilli()

	if source == "github" {
		event := h.Get("X-GitHub-Event")
		if event == "" {
			event = "event"
		}
		id := h.Get("X-GitHub-Delivery")
		if id == "" {
			id = "github:" + sha256Hex(body)
		}

		root := asMap(payload)
		repo := str(dig(root, "repository", "full_name"))
		level := "info"
		var text string

		switch event {
		case "push":
			text = str(dig(root, "head_commit", "message"))
			if text == "" {
				if commits, ok := dig(root, "commits").([]any); ok {
					text = fmt.Sprintf("%d commits", len(commits))
				}
			}
		case "pull_request":
			text = fmt.Sprintf("%s: %s", str(root["action"]), str(dig(root, "pull_request", "title")))
		case "issues":
			text = fmt.Sprintf("%s: %s", str(root["action"]), str(dig(root, "issue", "title")))
		case "workflow_run":
			action := str(root["action"])
			name := str(dig(root, "workflow_run", "name"))
			conclusion := str(dig(root, "workflow_run", "conclusion"))
			text = fmt.Sprintf("%s %s / %s", name, action, conclusion)
			if action == "completed" && conclusion == "failure" {
				level = "error"
			}
		case "release":
			text = fmt.Sprintf("%s: %s", str(root["action"]), str(dig(root, "release", "tag_name")))
		default:
			text = str(root["action"])
			if text == "" {
				text = string(body)
			}
		}

		title := "GitHub · " + event
		if repo != "" {
			title = "GitHub · " + repo
		}
		return Message{
			ID:     id,
			Source: "github",
			Title:  clamp(title, 120),
			Body:   clamp(text, 2000),
			Level:  level,
			Ts:     now,
		}
	}

	root := asMap(payload)
	id := source + ":" + sha256Hex(body)
	if v := str(root["id"]); v != "" {
		id = source + ":" + v
	}

	level := "info"
	switch strings.ToLower(firstNonEmpty(str(root["level"]), str(root["severity"]))) {
	case "error", "critical", "fatal":
		level = "error"
	case "warn", "warning":
		level = "warn"
	}

	ts := now
	if n, ok := toInt64(root["ts"]); ok && n > 1e12 {
		// 只认毫秒时间戳，避免把秒级时间戳当毫秒用
		ts = n
	}

	return Message{
		ID:     id,
		Source: source,
		Title: clamp(firstNonEmpty(
			str(root["title"]), str(root["subject"]), str(root["name"]), source+" 通知",
		), 120),
		Body: clamp(firstNonEmpty(
			str(root["body"]), str(root["text"]), str(root["message"]), str(root["content"]),
		), 2000),
		Level: level,
		Ts:    ts,
	}
}

func asMap(v any) map[string]any {
	m, _ := v.(map[string]any)
	return m
}

func dig(m map[string]any, keys ...string) any {
	var cur any = m
	for _, k := range keys {
		mm, ok := cur.(map[string]any)
		if !ok {
			return nil
		}
		cur = mm[k]
	}
	return cur
}

func str(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case float64:
		return strconv.FormatFloat(t, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(t)
	default:
		return ""
	}
}

func toInt64(v any) (int64, bool) {
	switch t := v.(type) {
	case float64:
		return int64(t), true
	case string:
		n, err := strconv.ParseInt(t, 10, 64)
		return n, err == nil
	default:
		return 0, false
	}
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

func clamp(s string, max int) string {
	if len(s) <= max {
		return s
	}
	// 按 rune 截断，别把中文切成半个字
	runes := []rune(s)
	if len(runes) <= max {
		return s
	}
	return string(runes[:max]) + "…"
}
