package egress

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestProxyValidationDoesNotExposeCredentials(t *testing.T) {
	for _, raw := range []string{"https://secret:password@proxy:80", "socks5://proxy:1080", "http://proxy:0", "http://proxy:65536", "http://proxy:", "http://proxy/path", "http://proxy?x=secret", "http://proxy#secret", "http://", "http://user:secret@proxy:bad", " ", "http://:secret@proxy:80", "http://user%3Aname:secret@proxy:80", "http://user:%0Asecret@proxy:80"} {
		if _, err := ParseProxy(raw); err == nil || strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "password") {
			t.Fatalf("invalid proxy accepted or sensitive error for test URL")
		}
	}
	for _, raw := range []string{"", "http://proxy", "http://proxy:8080/", "http://u:p@127.0.0.1:8080", "http://[::1]:8080"} {
		if _, err := ParseProxy(raw); err != nil {
			t.Fatal(err)
		}
	}
}

func TestHTTPSConnectUsesProxyAndKeepsProxyAuthOutsideTLS(t *testing.T) {
	var originCalls, proxyCalls atomic.Int32
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		originCalls.Add(1)
		if r.Header.Get("Proxy-Authorization") != "" {
			t.Error("proxy credential reached origin")
		}
		if r.Header.Get("Authorization") != "Bearer account-test" {
			t.Error("origin auth missing")
		}
		io.WriteString(w, "origin-ok")
	}))
	defer origin.Close()
	originURL, _ := url.Parse(origin.URL)
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proxyCalls.Add(1)
		if r.Method != "CONNECT" || r.Host != originURL.Host {
			t.Error("unexpected CONNECT target")
			w.WriteHeader(400)
			return
		}
		want := "Basic " + base64.StdEncoding.EncodeToString([]byte("proxy-user:proxy-pass"))
		if r.Header.Get("Proxy-Authorization") != want || r.Header.Get("Authorization") != "" {
			t.Error("CONNECT auth invalid")
			w.WriteHeader(407)
			return
		}
		up, err := net.DialTimeout("tcp", r.Host, time.Second)
		if err != nil {
			t.Error(err)
			w.WriteHeader(502)
			return
		}
		conn, buf, err := w.(http.Hijacker).Hijack()
		if err != nil {
			up.Close()
			t.Error(err)
			return
		}
		defer conn.Close()
		defer up.Close()
		buf.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n")
		buf.Flush()
		done := make(chan struct{})
		go func() { io.Copy(up, buf); up.Close(); close(done) }()
		io.Copy(conn, up)
		conn.Close()
		<-done
	}))
	defer proxy.Close()
	proxyURL, _ := url.Parse(proxy.URL)
	proxyURL.User = url.UserPassword("proxy-user", "proxy-pass")
	tr := AnthropicTransport(proxyURL.String())
	// Trust only the test origin certificate; production retains normal certificate validation.
	tr.TLSClientConfig = &tls.Config{RootCAs: origin.Client().Transport.(*http.Transport).TLSClientConfig.RootCAs}
	defer tr.CloseIdleConnections()
	c := &http.Client{Transport: tr, Timeout: 3 * time.Second}
	req, _ := http.NewRequest("GET", origin.URL+"/v1/messages", nil)
	req.Header.Set("Authorization", "Bearer account-test")
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, err := io.ReadAll(resp.Body)
	resp.Body.Close()
	if err != nil || string(b) != "origin-ok" || originCalls.Load() != 1 || proxyCalls.Load() != 1 {
		t.Fatal("CONNECT did not reach origin through proxy")
	}
}

func TestConfiguredProxyNeverFallsBackToDirect(t *testing.T) {
	var calls atomic.Int32
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.WriteHeader(200) }))
	defer origin.Close()
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(407) }))
	proxyURL := proxy.URL
	for _, raw := range []string{proxyURL, "http://proxy:0"} {
		tr := AnthropicTransport(raw)
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		req, _ := http.NewRequestWithContext(ctx, "GET", origin.URL, nil)
		resp, err := (&http.Client{Transport: tr}).Do(req)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode != 407 {
				t.Error("unexpected proxy response")
			}
		}
		cancel()
		tr.CloseIdleConnections()
	}
	proxy.Close()
	tr := AnthropicTransport(proxyURL)
	defer tr.CloseIdleConnections()
	if resp, err := (&http.Client{Transport: tr, Timeout: time.Second}).Get(origin.URL); err == nil {
		resp.Body.Close()
		t.Error("closed proxy unexpectedly succeeded")
	}
	if calls.Load() != 0 {
		t.Fatal("direct origin fallback occurred")
	}
}

func TestNoProxySettingPreservesDirectRouteDespiteGlobalEnvironment(t *testing.T) {
	t.Setenv("HTTP_PROXY", "http://127.0.0.1:1")
	t.Setenv("HTTPS_PROXY", "http://127.0.0.1:1")
	t.Setenv("NO_PROXY", "*")
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "direct") }))
	defer origin.Close()
	tr := AnthropicTransport("")
	defer tr.CloseIdleConnections()
	resp, err := (&http.Client{Transport: tr, Timeout: time.Second}).Get(origin.URL)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatal("existing direct route failed")
	}
}
