package egress

import (
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestParseAccountProxy(t *testing.T) {
	for _, tc := range []struct{ raw, want string }{
		{"http://user:pass@1.2.3.4:3128", "http://user:pass@1.2.3.4:3128"},
		{"http://proxy", "http://proxy:80"},
		{"https://proxy.example:443", "https://proxy.example:443"},
		{"https://proxy.example", "https://proxy.example:443"},
		{"socks5://user:pass@host:1080", "socks5://user:pass@host:1080"},
		{"socks5://host", "socks5://host:1080"},
		{"socks5h://host:1081", "socks5://host:1081"},
		{"socks5h://u:p@host", "socks5://u:p@host:1080"},
		{"http://[::1]", "http://[::1]:80"},
		{"http://proxy:8080/", "http://proxy:8080"},
	} {
		u, err := ParseAccountProxy(tc.raw)
		if err != nil {
			t.Errorf("%s: %v", RedactProxy(tc.raw), err)
			continue
		}
		if u.String() != tc.want {
			t.Errorf("%s parsed to %q, want %q", RedactProxy(tc.raw), u.String(), tc.want)
		}
	}
	long := strings.Repeat("s", 256)
	for _, raw := range []string{
		"", " ", "ftp://user:secret@proxy:21", "socks4://user:secret@proxy:1080", "proxy:8080", "//proxy:8080",
		"http://user:secret@proxy:0", "http://user:secret@proxy:65536", "http://user:secret@proxy:", "http://user:secret@proxy:bad",
		"http://user:secret@proxy/path", "http://user:secret@proxy?q=secret", "http://user:secret@proxy#secret",
		"http://:secret@proxy:80", "http://user:%0Asecret@proxy:80", "http://user%3Aname:secret@proxy:80",
		"socks5://user:" + long + "secret@proxy:1080", "http://user:secret@", "http:user:secret@proxy",
		"http://user:secret@proxy:80\n",
	} {
		_, err := ParseAccountProxy(raw)
		if err == nil {
			t.Errorf("accepted invalid proxy %q", RedactProxy(raw))
			continue
		}
		if !errors.Is(err, ErrInvalidAccountProxy) || strings.Contains(err.Error(), "secret") {
			t.Errorf("error for %q is not the redacted one: %v", RedactProxy(raw), err)
		}
	}
}

func TestRedactProxy(t *testing.T) {
	for raw, want := range map[string]string{
		"http://user:secret@1.2.3.4:3128": "http://1.2.3.4:3128",
		"socks5h://u:secret@host":         "socks5h://host",
		"http://user:secret@proxy:80\n":   "<invalid proxy URL>",
		"not a url":                       "<invalid proxy URL>",
	} {
		if got := RedactProxy(raw); got != want || strings.Contains(got, "secret") {
			t.Errorf("RedactProxy = %q, want %q", got, want)
		}
	}
}

func TestPoolCachesPerProxyAndBoundsSize(t *testing.T) {
	p := NewPool("")
	g1, err := p.Client("")
	if err != nil || g1 != p.global {
		t.Fatal("empty proxy URL must be the global client")
	}
	a1, _ := p.Client("http://a:1")
	a2, _ := p.Client("http://a:1")
	b, _ := p.Client("socks5://b:2")
	if a1 != a2 || a1 == b || a1 == g1 {
		t.Fatal("clients are not cached per proxy URL")
	}
	if _, err := p.Client("http://user:secret@proxy/path"); err == nil || strings.Contains(err.Error(), "secret") {
		t.Fatalf("invalid proxy: %v", err)
	}
	for i := 0; i < maxAccountClients+10; i++ {
		if _, err := p.Client("http://p" + strconv.Itoa(i) + ":1"); err != nil {
			t.Fatal(err)
		}
	}
	if n := len(p.clients); n > maxAccountClients {
		t.Fatalf("pool grew to %d", n)
	}
	// Concurrent lookups of one URL agree on one client.
	var wg sync.WaitGroup
	got := make([]*http.Client, 16)
	for i := range got {
		wg.Add(1)
		go func(i int) { defer wg.Done(); got[i], _ = p.Client("http://shared:1") }(i)
	}
	wg.Wait()
	for _, c := range got {
		if c != got[0] {
			t.Fatal("concurrent lookups produced different clients")
		}
	}
}

func TestPoolRoutesThroughAccountConnectProxy(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Proxy-Authorization") != "" {
			t.Error("proxy credential reached origin")
		}
		io.WriteString(w, "origin-ok")
	}))
	defer origin.Close()
	originURL, _ := url.Parse(origin.URL)
	var calls atomic.Int32
	proxy := connectProxy(t, originURL.Host, "acct-user:acct-pass", &calls)
	defer proxy.Close()
	proxyURL, _ := url.Parse(proxy.URL)
	proxyURL.User = url.UserPassword("acct-user", "acct-pass")

	// The global route is a dead proxy: the account's own must win, and nothing may go direct.
	p := NewPool("http://127.0.0.1:1")
	c, err := p.Client(proxyURL.String())
	if err != nil {
		t.Fatal(err)
	}
	trustOrigin(c, origin)
	if body := get(t, c, origin.URL); body != "origin-ok" || calls.Load() != 1 {
		t.Fatalf("body=%q connects=%d", body, calls.Load())
	}
	g, _ := p.Client("")
	trustOrigin(g, origin)
	if resp, err := g.Get(origin.URL); err == nil {
		resp.Body.Close()
		t.Fatal("global client bypassed its (dead) proxy")
	}
}

func TestPoolRoutesThroughSOCKS5(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "socks-ok")
	}))
	defer origin.Close()
	_, port, _ := net.SplitHostPort(strings.TrimPrefix(origin.URL, "https://"))
	// A name only the SOCKS server knows (it maps example.com to the test origin, which also
	// holds the cert for it): the request can only succeed if the proxy resolves the name.
	target := "https://example.com:" + port
	for _, tc := range []struct {
		name, user, pass string
	}{{"no auth", "", ""}, {"user/pass", "acct", "s3cret"}} {
		for _, scheme := range []string{"socks5", "socks5h"} {
			t.Run(tc.name+"/"+scheme, func(t *testing.T) {
				srv := startSOCKS5(t, tc.user, tc.pass)
				raw := scheme + "://" + srv.addr
				if tc.user != "" {
					raw = scheme + "://" + tc.user + ":" + tc.pass + "@" + srv.addr
				}
				c, err := NewPool("").Client(raw)
				if err != nil {
					t.Fatal(err)
				}
				trustOrigin(c, origin)
				if body := get(t, c, target); body != "socks-ok" {
					t.Fatalf("body = %q", body)
				}
				if got := srv.lastTarget(); got != "example.com:"+port {
					t.Fatalf("SOCKS CONNECT target = %q, want the unresolved name", got)
				}
			})
		}
	}
	// Wrong credentials are refused by the proxy; the request fails rather than going direct.
	srv := startSOCKS5(t, "acct", "right")
	c, _ := NewPool("").Client("socks5://acct:wrong@" + srv.addr)
	trustOrigin(c, origin)
	if resp, err := c.Get(target); err == nil {
		resp.Body.Close()
		t.Fatal("bad SOCKS credentials succeeded")
	} else if strings.Contains(err.Error(), "wrong") {
		t.Fatalf("error leaks the password: %v", err)
	}
}

func trustOrigin(c *http.Client, origin *httptest.Server) {
	// Trust only the test origin certificate; production keeps normal validation.
	c.Transport.(*http.Transport).TLSClientConfig = &tls.Config{RootCAs: origin.Client().Transport.(*http.Transport).TLSClientConfig.RootCAs}
	c.Timeout = 3 * time.Second
}

func get(t *testing.T, c *http.Client, u string) string {
	t.Helper()
	resp, err := c.Get(u)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return string(b)
}

// socks5Server is a minimal RFC 1928/1929 server: no-auth or username/password, CONNECT only.
type socks5Server struct {
	addr   string
	mu     sync.Mutex
	target string
}

func (s *socks5Server) lastTarget() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.target
}

func startSOCKS5(t *testing.T, user, pass string) *socks5Server {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	s := &socks5Server{addr: ln.Addr().String()}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go s.serve(conn, user, pass)
		}
	}()
	return s
}

func (s *socks5Server) serve(conn net.Conn, user, pass string) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 512)
	read := func(n int) []byte {
		if _, err := io.ReadFull(conn, buf[:n]); err != nil {
			return nil
		}
		return buf[:n]
	}
	// Greeting: VER NMETHODS METHODS...
	h := read(2)
	if h == nil || h[0] != 5 {
		return
	}
	methods := append([]byte(nil), read(int(h[1]))...)
	want := byte(0x00)
	if user != "" {
		want = 0x02
	}
	if !strings.ContainsRune(string(methods), rune(want)) {
		conn.Write([]byte{5, 0xff})
		return
	}
	conn.Write([]byte{5, want})
	if want == 0x02 {
		// RFC 1929: VER ULEN UNAME PLEN PASSWD
		v := read(2)
		if v == nil {
			return
		}
		u := string(read(int(v[1])))
		pl := read(1)
		if pl == nil {
			return
		}
		p := string(read(int(pl[0])))
		if u != user || p != pass {
			conn.Write([]byte{1, 1})
			return
		}
		conn.Write([]byte{1, 0})
	}
	// Request: VER CMD RSV ATYP DST.ADDR DST.PORT
	r := read(4)
	if r == nil || r[1] != 1 {
		conn.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	var host string
	switch r[3] {
	case 1:
		host = net.IP(append([]byte(nil), read(4)...)).String()
	case 3:
		l := read(1)
		host = string(read(int(l[0])))
	case 4:
		host = net.IP(append([]byte(nil), read(16)...)).String()
	default:
		return
	}
	port := binary.BigEndian.Uint16(read(2))
	target := net.JoinHostPort(host, fmt.Sprint(port))
	s.mu.Lock()
	s.target = target
	s.mu.Unlock()
	if host == "example.com" {
		host = "127.0.0.1"
	}
	up, err := net.DialTimeout("tcp", net.JoinHostPort(host, fmt.Sprint(port)), time.Second)
	if err != nil {
		conn.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer up.Close()
	conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	_ = conn.SetDeadline(time.Time{})
	done := make(chan struct{})
	go func() { io.Copy(up, conn); up.Close(); close(done) }()
	io.Copy(conn, up)
	conn.Close()
	<-done
}
