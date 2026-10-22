package proxy

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
)

func TestHandlerMissingTokenIs401(t *testing.T) {
	h := NewHandler(&config.Config{}, control.New("http://unused", "tok"))
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 401 {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestHandlerRetriesToSecondCandidate(t *testing.T) {
	// Upstream: first request 529, second serves a real body.
	var calls int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt32(&calls, 1)
		if n == 1 {
			w.WriteHeader(529)
			_, _ = io.WriteString(w, `{"error":"overloaded"}`)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(200)
		_, _ = io.WriteString(w, `{"model":"m","usage":{"input_tokens":1},"served_by":"second"}`)
	}))
	defer upstream.Close()

	// Control service: resolve → 2 candidates; usage → 204.
	control_ := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/internal/resolve":
			w.Header().Set("Content-Type", "application/json")
			_, _ = fmt.Fprint(w, `{"userId":1,"overLimit":false,"candidates":[
				{"accountId":1,"type":"OAUTH","authHeaders":{}},
				{"accountId":2,"type":"OAUTH","authHeaders":{}}]}`)
		case "/internal/usage":
			w.WriteHeader(204)
		default:
			w.WriteHeader(404)
		}
	}))
	defer control_.Close()

	cfg := &config.Config{UpstreamBaseURL: upstream.URL}
	h := NewHandler(cfg, control.New(control_.URL, "tok"))

	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{}`))
	req.Header.Set("Authorization", "Bearer cxp_token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != 200 {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "second") {
		t.Errorf("client did not see second candidate's body: %s", rec.Body.String())
	}
	if atomic.LoadInt32(&calls) != 2 {
		t.Errorf("upstream calls = %d, want 2", calls)
	}
}

func TestHandlerNoCandidatesIs503(t *testing.T) {
	control_ := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/internal/resolve" {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"userId":1,"overLimit":false,"candidates":[]}`)
			return
		}
		w.WriteHeader(204)
	}))
	defer control_.Close()

	h := NewHandler(&config.Config{UpstreamBaseURL: "http://unused"}, control.New(control_.URL, "tok"))
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{}`))
	req.Header.Set("Authorization", "Bearer cxp_token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 503 {
		t.Errorf("status = %d, want 503", rec.Code)
	}
}

// Parked accounts are a rate limit, not an outage: 429 with Retry-After so clients back off.
func TestHandlerParkedPoolIs429WithRetryAfter(t *testing.T) {
	control_ := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/internal/resolve" {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"userId":1,"overLimit":false,"candidates":[],"retryAfterSeconds":42}`)
			return
		}
		w.WriteHeader(204)
	}))
	defer control_.Close()

	h := NewHandler(&config.Config{UpstreamBaseURL: "http://unused"}, control.New(control_.URL, "tok"))
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{}`))
	req.Header.Set("Authorization", "Bearer cxp_token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 429 {
		t.Fatalf("status = %d, want 429", rec.Code)
	}
	if got := rec.Header().Get("Retry-After"); got != "42" {
		t.Errorf("Retry-After = %q, want 42", got)
	}
	if !strings.Contains(rec.Body.String(), "rate_limit_error") {
		t.Errorf("body = %s", rec.Body.String())
	}
}

func TestHandlerOverLimitIs429(t *testing.T) {
	control_ := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/internal/resolve" {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"userId":1,"overLimit":true,"candidates":[]}`)
			return
		}
		w.WriteHeader(204)
	}))
	defer control_.Close()

	h := NewHandler(&config.Config{UpstreamBaseURL: "http://unused"}, control.New(control_.URL, "tok"))
	req := httptest.NewRequest("POST", "/v1/messages", strings.NewReader(`{}`))
	req.Header.Set("Authorization", "Bearer cxp_token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 429 {
		t.Errorf("status = %d, want 429", rec.Code)
	}
}
