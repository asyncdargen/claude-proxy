package proxy

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"claudeproxy/gateway/internal/control"
)

func TestForwardUsesAccountCredentialsAndIdentityWithoutClientOrigin(t *testing.T) {
	for _, tc := range []struct {
		name string
		auth http.Header
	}{
		{"oauth", http.Header{"Authorization": {"Bearer fake-connected-oauth"}}},
		{"api_key", http.Header{"X-Api-Key": {"fake-connected-api-key"}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			type receivedRequest struct {
				headers http.Header
				body    []byte
			}
			received := make(chan receivedRequest, 1)
			upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, _ := io.ReadAll(r.Body)
				received <- receivedRequest{r.Header.Clone(), body}
				w.Header().Set("Content-Type", "application/json")
				_, _ = io.WriteString(w, `{"type":"message","model":"claude-opus-4-8","usage":{"input_tokens":1,"output_tokens":1}}`)
			}))
			defer upstream.Close()

			body := `{"model":"claude-opus-4-8","max_tokens":32,"messages":[{"role":"user","content":"hello"}],"metadata":{"user_id":"{\"device_id\":\"client-device\",\"account_uuid\":\"client-account\",\"session_id\":\"client-session\"}"}}`
			req := httptest.NewRequest(http.MethodPost, "/v1/messages", strings.NewReader(body))
			req.RemoteAddr = "198.51.100.23:1234"
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Authorization", "Bearer fake-client-token")
			req.Header.Set("X-Api-Key", "fake-client-api-key")
			req.Header.Set("X-Claude-Code-Session-Id", "client-session")
			req.Header.Set("Anthropic-Version", "2023-06-01")
			req.Header.Set("Anthropic-Beta", "context-management-2025-06-27")
			privateHeaders := []string{
				"Cookie", "Cookie2", "Proxy-Authorization", "Proxy-Authenticate", "Forwarded",
				"X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port",
				"X-Forwarded-Client-Ip", "X-Real-Ip", "X-Client-Ip", "X-Cluster-Client-Ip",
				"X-Originating-Ip", "X-Original-Forwarded-For", "CF-Connecting-Ip",
				"CF-Connecting-IPv6", "True-Client-Ip", "Fastly-Client-Ip", "X-Client-Id", "X-Stainless-Lang",
			}
			for _, name := range privateHeaders {
				req.Header.Set(name, "client-private-198.51.100.23")
			}
			cand := control.Candidate{AccountID: 7, DeviceID: "connected-device", AccountUUID: "connected-account", AuthHeaders: map[string]string{
				"Anthropic-Beta": "oauth-2025-04-20",
			}}
			for name := range tc.auth {
				cand.AuthHeaders[name] = tc.auth.Get(name)
			}
			rec := httptest.NewRecorder()
			result := testHandler(upstream.URL).forward(context.Background(), rec, req, cand, []control.Candidate{cand}, 0, nil, nil, []byte(body), false, false)
			if rec.Code != http.StatusOK || result.retry {
				t.Fatalf("forward result: status=%d retry=%v", rec.Code, result.retry)
			}
			got := <-received
			for _, name := range privateHeaders {
				if values := got.headers.Values(name); len(values) != 0 {
					t.Errorf("client header %s leaked: %q", name, values)
				}
			}
			for _, name := range []string{"Authorization", "X-Api-Key"} {
				if value := got.headers.Get(name); value != tc.auth.Get(name) {
					t.Errorf("%s = %q, want connected account value %q", name, value, tc.auth.Get(name))
				}
			}
			if got.headers.Get("Anthropic-Version") != "2023-06-01" || got.headers.Get("Content-Type") != "application/json" {
				t.Error("provider protocol headers were lost")
			}
			if got.headers.Get("Anthropic-Beta") != "context-management-2025-06-27,oauth-2025-04-20" {
				t.Errorf("provider beta merge = %q", got.headers.Get("Anthropic-Beta"))
			}
			var payload struct {
				Metadata struct {
					UserID string `json:"user_id"`
				} `json:"metadata"`
			}
			if err := json.Unmarshal(got.body, &payload); err != nil {
				t.Fatal(err)
			}
			var identity map[string]string
			if err := json.Unmarshal([]byte(payload.Metadata.UserID), &identity); err != nil {
				t.Fatal(err)
			}
			if identity["device_id"] != cand.DeviceID || identity["account_uuid"] != cand.AccountUUID {
				t.Errorf("upstream identity = %v", identity)
			}
			session := got.headers.Get("X-Claude-Code-Session-Id")
			if session == "" || session == "client-session" || identity["session_id"] != session {
				t.Errorf("session rotation: header=%q body=%q", session, identity["session_id"])
			}
		})
	}
}
