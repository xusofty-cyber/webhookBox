package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
)

var (
	errMissingSig = errors.New("missing_signature")
	errBadSig     = errors.New("bad_signature")
	errBadToken   = errors.New("bad_token")
	errNoAuth     = errors.New("unauthorized")
)

func tokenOK(provided, expected string) bool {
	if expected == "" || provided == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(provided), []byte(expected)) == 1
}

// verify 的优先级和 Worker 版本保持一致：
//  1. github + GITHUB_SECRET  -> X-Hub-Signature-256
//  2. gitlab + GENERIC_SECRET -> X-Gitlab-Token
//  3. GENERIC_SECRET + X-HookBox-Signature -> 通用 HMAC
//  4. 兜底：URL 上带 ?token=<RELAY_TOKEN>
func (s *server) verify(source string, r *http.Request, body []byte) error {
	if source == "github" && s.cfg.GitHubSecret != "" {
		provided := r.Header.Get("X-Hub-Signature-256")
		if provided == "" {
			return errMissingSig
		}
		expected := "sha256=" + hmacHex(s.cfg.GitHubSecret, body)
		if !hmac.Equal([]byte(provided), []byte(expected)) {
			return errBadSig
		}
		return nil
	}

	if source == "gitlab" && s.cfg.GenericSecret != "" {
		if !hmac.Equal([]byte(r.Header.Get("X-Gitlab-Token")), []byte(s.cfg.GenericSecret)) {
			return errBadToken
		}
		return nil
	}

	if s.cfg.GenericSecret != "" {
		if provided := r.Header.Get("X-HookBox-Signature"); provided != "" {
			expected := hmacHex(s.cfg.GenericSecret, body)
			normalized := strings.TrimPrefix(strings.ToLower(provided), "sha256=")
			if !hmac.Equal([]byte(normalized), []byte(expected)) {
				return errBadSig
			}
			return nil
		}
	}

	if tokenOK(r.URL.Query().Get("token"), s.cfg.RelayToken) {
		return nil
	}
	return errNoAuth
}

func hmacHex(secret string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

func sha256Hex(body []byte) string {
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}
