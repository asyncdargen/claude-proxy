package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"claudeproxy/gateway/internal/config"
)

func TestAllAnthropicRoutesUseConfiguredProxyAndDirectControl(t *testing.T) {
	t.Setenv("NO_PROXY", "*") // Explicit egress must not be bypassed by global environment.
	var resolves, proxied atomic.Int32
	ctrl := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Internal-Token") != "internal-test" {
			t.Error("control credential missing")
		}
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/internal/resolve" {
			resolves.Add(1)
			io.WriteString(w, `{"candidates":[{"accountId":1,"type":"OAUTH","authHeaders":{"Authorization":"Bearer account-test"}}]}`)
		} else {
			io.WriteString(w, `{}`)
		}
	}))
	defer ctrl.Close()
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proxied.Add(1)
		if r.URL.Host != "anthropic.invalid" || r.Header.Get("X-Internal-Token") != "" {
			t.Error("wrong destination or internal control sent to proxy")
		}
		if r.Header.Get("Authorization") != "Bearer account-test" || r.Header.Get("X-Api-Key") != "" {
			t.Error("client credentials leaked or account credentials missing")
		}
		if r.Header.Get("X-Real-IP") != "" || r.Header.Get("X-Forwarded-For") != "" {
			t.Error("client origin leaked")
		}
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"type":"message","id":"msg-test","role":"assistant","model":"claude-sonnet-5","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}`)
	}))
	defer proxy.Close()
	cfg := &config.Config{ServiceURL: ctrl.URL, InternalToken: "internal-test", UpstreamBaseURL: "http://anthropic.invalid", AnthropicProxyURL: proxy.URL}
	mux := newMux(cfg)
	for _, path := range []string{"/v1/messages", "/routing/anthropic/v1/messages", "/routing/openai/v1/chat/completions"} {
		req := httptest.NewRequest("POST", path, strings.NewReader(`{"model":"claude-sonnet-5","max_tokens":32,"messages":[{"role":"user","content":"hello"}]}`))
		req.Header.Set("Authorization", "Bearer client-test")
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Real-IP", "198.51.100.23")
		req.Header.Set("X-Forwarded-For", "198.51.100.23")
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != 200 {
			t.Errorf("%s: status=%d body=%s", path, rec.Code, rec.Body.String())
		}
	}
	if proxied.Load() != 3 || resolves.Load() != 3 {
		t.Fatalf("proxy=%d resolves=%d", proxied.Load(), resolves.Load())
	}
}

func TestAnthropicRedirectDoesNotForwardCredentials(t *testing.T) {
	var leaked atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { leaked.Add(1) }))
	defer other.Close()
	ctrl := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"candidates":[{"accountId":1,"type":"API_KEY","authHeaders":{"x-api-key":"account-test"}}]}`)
	}))
	defer ctrl.Close()
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", other.URL)
		w.WriteHeader(307)
	}))
	defer upstream.Close()
	mux := newMux(&config.Config{ServiceURL: ctrl.URL, UpstreamBaseURL: upstream.URL})
	for _, path := range []string{"/v1/messages", "/routing/anthropic/v1/messages", "/routing/openai/v1/chat/completions"} {
		req := httptest.NewRequest("POST", path, strings.NewReader(`{"model":"claude-sonnet-5","max_tokens":32,"messages":[{"role":"user","content":"hello"}]}`))
		req.Header.Set("Authorization", "Bearer client-test")
		mux.ServeHTTP(httptest.NewRecorder(), req)
	}
	if leaked.Load() != 0 {
		t.Fatal("upstream redirect received account credentials")
	}
}
