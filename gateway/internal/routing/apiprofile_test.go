package routing_test

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"claudeproxy/gateway/internal/anthropicgw"
	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
	"claudeproxy/gateway/internal/routing"
)

type upstreamSeen struct {
	uri     string
	headers http.Header
	body    map[string]any
	rawLen  int
	cl      int64
}

// routeOnce serves one routing request for the given candidates and returns what each upstream
// attempt carried.
func routeOnce(t *testing.T, defaultBase string, cands []control.Candidate, seen <-chan upstreamSeen) (*httptest.ResponseRecorder, []upstreamSeen) {
	t.Helper()
	controlSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/internal/resolve":
			_ = json.NewEncoder(w).Encode(map[string]any{"userId": 1, "candidates": cands})
		default:
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	defer controlSrv.Close()
	cfg := &config.Config{UpstreamBaseURL: defaultBase, ClaudeCodeUserAgent: "claude-cli/2.1.300 (external, cli)"}
	h := routing.NewHandler(cfg, control.New(controlSrv.URL, "secret").WithSource("routing"), anthropicgw.New())
	req := httptest.NewRequest(http.MethodPost, "/v1/messages?beta=true",
		strings.NewReader(`{"model":"claude-sonnet-4-5","system":"Mine <x>","metadata":{"user_id":"sdk-caller"},"messages":[{"role":"user","content":"hi"}]}`))
	req.Header.Set("x-api-key", "cxr_test")
	req.Header.Set("anthropic-beta", "context-management-2025-06-27")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, req)
	var got []upstreamSeen
	for len(seen) > 0 {
		got = append(got, <-seen)
	}
	return rr, got
}

func recorder(t *testing.T, status int) (*httptest.Server, chan upstreamSeen) {
	seen := make(chan upstreamSeen, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var body map[string]any
		_ = json.Unmarshal(raw, &body)
		seen <- upstreamSeen{r.URL.RequestURI(), r.Header.Clone(), body, len(raw), r.ContentLength}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, `{"id":"m","model":"claude-sonnet-4-5","content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}`)
	}))
	t.Cleanup(srv.Close)
	return srv, seen
}

func TestRoutingAppliesAPIKeyProfile(t *testing.T) {
	upstream, seen := recorder(t, 200)

	// ANTHROPIC_API: everything routing adds to look like the CLI comes back off.
	rr, got := routeOnce(t, upstream.URL, []control.Candidate{{AccountID: 1, Type: "API_KEY", APIKeyMode: "ANTHROPIC_API", DeviceID: "d",
		AuthHeaders: map[string]string{"x-api-key": "sk-ant-api"}}}, seen)
	if rr.Code != 200 || len(got) != 1 {
		t.Fatalf("status=%d attempts=%d", rr.Code, len(got))
	}
	g := got[0]
	if g.headers.Get("x-app") != "" || g.headers.Get("X-Claude-Code-Session-Id") != "" || strings.HasPrefix(g.headers.Get("User-Agent"), "claude-cli/") {
		t.Errorf("CLI headers survived: %v", g.headers)
	}
	if g.headers.Get("anthropic-beta") != "context-management-2025-06-27" {
		t.Errorf("beta = %q", g.headers.Get("anthropic-beta"))
	}
	if _, ok := g.body["metadata"]; ok {
		t.Errorf("stamped identity survived: %v", g.body["metadata"])
	}
	sys, _ := g.body["system"].([]any)
	if len(sys) != 1 || sys[0].(map[string]any)["text"] != "Mine <x>" {
		t.Errorf("system = %v", g.body["system"])
	}
	if g.cl != int64(g.rawLen) {
		t.Errorf("Content-Length %d for a %d-byte body", g.cl, g.rawLen)
	}

	// AGENT_SDK: the injected CLI sentence becomes the SDK's, the identity stays.
	rr, got = routeOnce(t, upstream.URL, []control.Candidate{{AccountID: 1, Type: "API_KEY", APIKeyMode: "AGENT_SDK", DeviceID: "d",
		AuthHeaders: map[string]string{"x-api-key": "sk-ant-api"}}}, seen)
	if rr.Code != 200 || len(got) != 1 {
		t.Fatalf("status=%d attempts=%d", rr.Code, len(got))
	}
	g = got[0]
	if g.headers.Get("User-Agent") != "claude-cli/2.1.300 (external, sdk-ts, agent-sdk/0.3.310)" || g.headers.Get("x-app") != "cli" {
		t.Errorf("sdk headers: %v", g.headers)
	}
	if g.headers.Get("anthropic-beta") != "context-management-2025-06-27,claude-code-20250219" {
		t.Errorf("beta = %q", g.headers.Get("anthropic-beta"))
	}
	sys, _ = g.body["system"].([]any)
	if len(sys) != 2 || sys[0].(map[string]any)["text"] != "You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK." {
		t.Errorf("system = %v", g.body["system"])
	}
	if g.body["metadata"] == nil {
		t.Error("agent sdk keeps the stamped identity")
	}
}

func TestRoutingCustomBaseAndAccountProxy(t *testing.T) {
	upstream, seen := recorder(t, 200)
	rr, got := routeOnce(t, "http://127.0.0.1:1", []control.Candidate{
		{AccountID: 1, Type: "API_KEY", BaseURL: "not a url", AuthHeaders: map[string]string{"x-api-key": "a"}},
		{AccountID: 2, Type: "API_KEY", ProxyURL: "gopher://proxy:70", AuthHeaders: map[string]string{"x-api-key": "b"}},
		{AccountID: 3, Type: "API_KEY", BaseURL: upstream.URL + "/gateway", AuthHeaders: map[string]string{"x-api-key": "c"}},
	}, seen)
	if rr.Code != 200 || len(got) != 1 {
		t.Fatalf("status=%d attempts=%d body=%s", rr.Code, len(got), rr.Body.String())
	}
	if got[0].uri != "/gateway/v1/messages?beta=true" || got[0].headers.Get("x-api-key") != "c" {
		t.Fatalf("upstream saw uri=%q key=%q", got[0].uri, got[0].headers.Get("x-api-key"))
	}

	// An account proxy carries a custom base too.
	var proxied atomic.Int32
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proxied.Add(1)
		if r.URL.String() != "http://other.invalid/gw/v1/messages?beta=true" {
			t.Errorf("proxied URL = %q", r.URL.String())
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"m","content":[],"usage":{"input_tokens":1,"output_tokens":1}}`)
	}))
	defer proxy.Close()
	rr, _ = routeOnce(t, "http://127.0.0.1:1", []control.Candidate{
		{AccountID: 4, Type: "API_KEY", BaseURL: "http://other.invalid/gw/", ProxyURL: proxy.URL, AuthHeaders: map[string]string{"x-api-key": "d"}},
	}, seen)
	if rr.Code != 200 || proxied.Load() != 1 {
		t.Fatalf("status=%d proxied=%d", rr.Code, proxied.Load())
	}

	// The last candidate failing locally still answers the client.
	rr, _ = routeOnce(t, upstream.URL, []control.Candidate{
		{AccountID: 5, Type: "API_KEY", ProxyURL: "socks4://proxy:1080", AuthHeaders: map[string]string{"x-api-key": "e"}},
	}, seen)
	if rr.Code != http.StatusBadGateway || len(seen) != 0 {
		t.Fatalf("status=%d, want 502 without an upstream call", rr.Code)
	}
}
