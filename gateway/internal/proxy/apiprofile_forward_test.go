package proxy

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"

	"claudeproxy/gateway/internal/control"
)

type seenRequest struct {
	uri           string
	headers       http.Header
	body          []byte
	contentLength int64
}

// recordingUpstream answers every request with a small JSON message and hands what it saw back.
func recordingUpstream(t *testing.T) (*httptest.Server, chan seenRequest) {
	t.Helper()
	seen := make(chan seenRequest, 4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		seen <- seenRequest{r.URL.RequestURI(), r.Header.Clone(), body, r.ContentLength}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"type":"message","model":"claude-opus-4-8","usage":{"input_tokens":1,"output_tokens":1}}`)
	}))
	t.Cleanup(srv.Close)
	return srv, seen
}

func TestForwardAppliesAPIKeyProfile(t *testing.T) {
	const ccBody = `{"model":"claude-opus-4-8","max_tokens":8,"system":[{"type":"text","text":"x-anthropic-billing-header: cc_version=2.1.300; cc_entrypoint=cli;"},{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude.","cache_control":{"type":"ephemeral"}},{"type":"text","text":"Rules <b>"}],"messages":[{"role":"user","content":"hi"}],"metadata":{"user_id":"{\"device_id\":\"client-device\",\"account_uuid\":\"\",\"session_id\":\"client-session\"}"}}`
	for _, tc := range []struct {
		name, accountType, mode string
		check                   func(t *testing.T, got seenRequest, body map[string]any)
	}{
		{"claude code key keeps the CLI shape", "API_KEY", "CLAUDE_CODE", func(t *testing.T, got seenRequest, body map[string]any) {
			if got.headers.Get("x-app") != "cli" || got.headers.Get("User-Agent") != "claude-cli/2.1.300 (external, cli)" {
				t.Errorf("CLI headers changed: %v", got.headers)
			}
			if b := strings.Join(got.headers.Values("Anthropic-Beta"), ","); b != "claude-code-20250219,oauth-2025-04-20,context-management-2025-06-27" {
				t.Errorf("beta = %q", b)
			}
			if body["metadata"] == nil || len(body["system"].([]any)) != 3 {
				t.Errorf("body changed: %v", body)
			}
		}},
		{"anthropic api key", "API_KEY", "ANTHROPIC_API", func(t *testing.T, got seenRequest, body map[string]any) {
			if got.headers.Get("x-app") != "" || got.headers.Get("X-Claude-Code-Session-Id") != "" || got.headers.Get("User-Agent") != "claude-proxy/1.0" {
				t.Errorf("CLI headers survived: %v", got.headers)
			}
			if got.headers.Get("Anthropic-Beta") != "context-management-2025-06-27" {
				t.Errorf("beta = %q", got.headers.Get("Anthropic-Beta"))
			}
			if _, ok := body["metadata"]; ok {
				t.Errorf("identity metadata survived: %v", body["metadata"])
			}
			sys := body["system"].([]any)
			if len(sys) != 1 || sys[0].(map[string]any)["text"] != "Rules <b>" {
				t.Errorf("system = %v", sys)
			}
		}},
		{"agent sdk key", "API_KEY", "AGENT_SDK", func(t *testing.T, got seenRequest, body map[string]any) {
			if got.headers.Get("x-app") != "cli" || got.headers.Get("User-Agent") != "claude-cli/2.1.300 (external, sdk-ts, agent-sdk/0.3.310)" {
				t.Errorf("sdk headers: %v", got.headers)
			}
			if got.headers.Get("Anthropic-Beta") != "claude-code-20250219,context-management-2025-06-27" {
				t.Errorf("beta = %q", got.headers.Get("Anthropic-Beta"))
			}
			sys := body["system"].([]any)
			first := sys[0].(map[string]any)
			if len(sys) != 2 || first["text"] != "You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK." || first["cache_control"] == nil {
				t.Errorf("system = %v", sys)
			}
			if body["metadata"] == nil {
				t.Error("agent sdk keeps the (rotated) identity")
			}
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			upstream, seen := recordingUpstream(t)
			req := httptest.NewRequest(http.MethodPost, "/v1/messages?beta=true", strings.NewReader(ccBody))
			req.Header.Set("User-Agent", "claude-cli/2.1.300 (external, cli)")
			req.Header.Set("x-app", "cli")
			req.Header.Set("X-Claude-Code-Session-Id", "client-session")
			req.Header.Add("Anthropic-Beta", "claude-code-20250219,oauth-2025-04-20")
			req.Header.Add("Anthropic-Beta", "context-management-2025-06-27")
			cand := control.Candidate{AccountID: 3, Type: tc.accountType, APIKeyMode: tc.mode, DeviceID: "acct-device",
				AuthHeaders: map[string]string{"x-api-key": "sk-ant-api-test"}}
			rec := httptest.NewRecorder()
			res := testHandler(upstream.URL).forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, []byte(ccBody), false, false)
			if rec.Code != 200 || res.retry {
				t.Fatalf("status=%d retry=%v", rec.Code, res.retry)
			}
			got := <-seen
			if got.contentLength != int64(len(got.body)) || got.headers.Get("Content-Length") != "" && got.headers.Get("Content-Length") != strconv.Itoa(len(got.body)) {
				t.Errorf("Content-Length %d does not match the %d-byte body", got.contentLength, len(got.body))
			}
			if got.uri != "/v1/messages?beta=true" {
				t.Errorf("upstream uri = %q", got.uri)
			}
			if got.headers.Get("x-api-key") != "sk-ant-api-test" {
				t.Error("account key missing")
			}
			var body map[string]any
			if err := json.Unmarshal(got.body, &body); err != nil {
				t.Fatalf("upstream body: %v: %s", err, got.body)
			}
			if strings.Contains(string(got.body), `\u003c`) {
				t.Errorf("body was HTML-escaped: %s", got.body)
			}
			tc.check(t, got, body)
		})
	}
}

func TestForwardCustomBaseURL(t *testing.T) {
	upstream, seen := recordingUpstream(t)
	cand := control.Candidate{AccountID: 4, Type: "API_KEY", BaseURL: upstream.URL + "/gateway/", AuthHeaders: map[string]string{"Authorization": "Bearer cxp_other"}}
	req := httptest.NewRequest(http.MethodPost, "/v1/messages?beta=true", strings.NewReader(`{"model":"m"}`))
	rec := httptest.NewRecorder()
	// The default upstream is unreachable: only the account's own base may be used.
	res := testHandler("http://127.0.0.1:1").forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, []byte(`{"model":"m"}`), false, false)
	if rec.Code != 200 || res.retry {
		t.Fatalf("status=%d retry=%v body=%s", rec.Code, res.retry, rec.Body.String())
	}
	if got := <-seen; got.uri != "/gateway/v1/messages?beta=true" || got.headers.Get("Authorization") != "Bearer cxp_other" {
		t.Fatalf("upstream saw uri=%q auth=%q", got.uri, got.headers.Get("Authorization"))
	}
}

func TestForwardBadAccountSettingsFailOverWithoutGoingDirect(t *testing.T) {
	for _, bad := range []control.Candidate{
		{AccountID: 1, Type: "API_KEY", ProxyURL: "ftp://user:secret@proxy:21"},
		{AccountID: 1, Type: "API_KEY", BaseURL: "file:///etc/passwd"},
		{AccountID: 1, Type: "API_KEY", BaseURL: "https://user:secret@other.example"},
	} {
		upstream, seen := recordingUpstream(t)
		good := control.Candidate{AccountID: 2, Type: "API_KEY", AuthHeaders: map[string]string{"x-api-key": "good"}}
		bad.AuthHeaders = map[string]string{"x-api-key": "bad"}
		cands := []control.Candidate{bad, good}
		h := testHandler(upstream.URL)
		req := httptest.NewRequest(http.MethodPost, "/v1/messages", strings.NewReader(`{}`))
		rec := httptest.NewRecorder()
		res := h.forward(context.Background(), rec, req, bad, cands, 0, nil, nil, []byte(`{}`), true, false)
		if !res.retry || res.report.AccountID != 1 || res.report.Status != 0 {
			t.Fatalf("bad candidate must be a retryable failure: %+v", res)
		}
		if len(seen) != 0 {
			t.Fatal("bad candidate reached upstream")
		}
		// As the last candidate it answers 502 instead of an empty response.
		rec = httptest.NewRecorder()
		res = h.forward(context.Background(), rec, req, bad, cands[:1], 0, nil, nil, []byte(`{}`), false, false)
		if res.retry || rec.Code != http.StatusBadGateway || len(seen) != 0 {
			t.Fatalf("last bad candidate: status=%d retry=%v", rec.Code, res.retry)
		}
		res = h.forward(context.Background(), httptest.NewRecorder(), req, good, cands, 1, nil, nil, []byte(`{}`), false, false)
		if got := <-seen; res.retry || got.headers.Get("x-api-key") != "good" {
			t.Fatalf("next candidate did not serve: %+v", res)
		}
	}
}

func TestForwardUsesAccountProxy(t *testing.T) {
	var proxied atomic.Int32
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proxied.Add(1)
		// A plain-http origin is fetched through a forward proxy in absolute form.
		if r.URL.Host != "anthropic.invalid" || r.Header.Get("Proxy-Authorization") == "" || r.Header.Get("x-api-key") != "acct" {
			t.Errorf("unexpected proxied request: host=%q headers=%v", r.URL.Host, r.Header)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"type":"message","usage":{"input_tokens":1,"output_tokens":1}}`)
	}))
	defer proxy.Close()
	cand := control.Candidate{AccountID: 5, Type: "API_KEY", ProxyURL: strings.Replace(proxy.URL, "http://", "http://u:p@", 1),
		AuthHeaders: map[string]string{"x-api-key": "acct"}}
	req := httptest.NewRequest(http.MethodPost, "/v1/messages", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()
	res := testHandler("http://anthropic.invalid").forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, []byte(`{}`), false, false)
	if rec.Code != 200 || res.retry || proxied.Load() != 1 {
		t.Fatalf("status=%d retry=%v proxied=%d", rec.Code, res.retry, proxied.Load())
	}
}
