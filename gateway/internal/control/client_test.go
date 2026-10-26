package control

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestResolveParsesCandidatesAndSendsToken(t *testing.T) {
	var gotToken string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/resolve" {
			t.Errorf("path = %s", r.URL.Path)
		}
		gotToken = r.Header.Get("X-Internal-Token")
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"userId":7,"overLimit":false,"dailyLimitUsd":25.0,"usedUsd":3.4,
			"candidates":[{"accountId":12,"type":"OAUTH","deviceId":"dev1",
			"authHeaders":{"Authorization":"Bearer sk","anthropic-beta":"oauth-2025-04-20"},
			"fiveHourResetEpoch":1783770000,"weeklyResetEpoch":1784300000}]}`)
	}))
	defer srv.Close()

	c := New(srv.URL, "tok")
	resp, status, err := c.Resolve(context.Background(), "cxp_x", "POST", "/v1/messages")
	if err != nil {
		t.Fatal(err)
	}
	if status != 200 {
		t.Fatalf("status = %d", status)
	}
	if gotToken != "tok" {
		t.Errorf("X-Internal-Token = %q", gotToken)
	}
	if resp.UserID == nil || *resp.UserID != 7 {
		t.Errorf("UserID = %v", resp.UserID)
	}
	if len(resp.Candidates) != 1 {
		t.Fatalf("candidates = %d", len(resp.Candidates))
	}
	c0 := resp.Candidates[0]
	if c0.AccountID != 12 || c0.Type != "OAUTH" {
		t.Errorf("candidate = %+v", c0)
	}
	if c0.AuthHeaders["Authorization"] != "Bearer sk" {
		t.Errorf("auth header = %q", c0.AuthHeaders["Authorization"])
	}
	if c0.FiveHourResetEpoch != 1783770000 {
		t.Errorf("fiveHour = %d", c0.FiveHourResetEpoch)
	}
}

func TestResolveReturnsStatusOnError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()
	c := New(srv.URL, "tok")
	resp, status, err := c.Resolve(context.Background(), "bad", "POST", "/v1/messages")
	if err != nil {
		t.Fatal(err)
	}
	if status != 401 {
		t.Errorf("status = %d, want 401", status)
	}
	if resp != nil {
		t.Errorf("resp = %+v, want nil", resp)
	}
}

func TestReportUsagePosts(t *testing.T) {
	var mu sync.Mutex
	var got UsageReport
	done := make(chan struct{}, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/usage" {
			t.Errorf("path = %s", r.URL.Path)
		}
		mu.Lock()
		_ = json.NewDecoder(r.Body).Decode(&got)
		mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
		select {
		case done <- struct{}{}:
		default:
		}
	}))
	defer srv.Close()

	c := New(srv.URL, "tok")
	model := "claude-opus-4-8"
	c.ReportUsage(context.Background(), UsageReport{AccountID: 12, Input: 100, Output: 20, Status: 200, Model: &model})

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("usage report not received")
	}
	mu.Lock()
	defer mu.Unlock()
	if got.AccountID != 12 || got.Input != 100 || got.Status != 200 {
		t.Errorf("got = %+v", got)
	}
}

func TestModelsFetchesCatalogue(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/models" || r.Header.Get("X-Internal-Token") != "secret" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_, _ = w.Write([]byte(`[{"id":"claude-opus-5-5","displayName":"Claude Opus 5.5","createdAt":"2026-09-01T00:00:00Z"}]`))
	}))
	defer srv.Close()
	got, err := New(srv.URL, "secret").Models(context.Background())
	if err != nil || len(got) != 1 || got[0].ID != "claude-opus-5-5" || got[0].DisplayName != "Claude Opus 5.5" {
		t.Fatalf("Models() = %v, %v", got, err)
	}
	if _, err := New(srv.URL, "wrong").Models(context.Background()); err == nil {
		t.Fatalf("a 401 must surface as an error, not an empty list")
	}
}
