package egress

import (
	"errors"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// ErrInvalidAccountProxy is returned for a per-account proxy URL that fails validation. It says
// nothing about the URL itself: the URL carries the proxy's credentials.
var ErrInvalidAccountProxy = errors.New("account proxy URL must be http://, https://, socks5:// or socks5h:// with a valid host[:port], no path, query or fragment")

var defaultProxyPorts = map[string]string{"http": "80", "https": "443", "socks5": "1080", "socks5h": "1080"}

// ParseAccountProxy validates a per-account outbound proxy (already decrypted by the service).
// A missing port is filled in explicitly with the scheme's default, so the URL the transport
// dials is exactly the one the cache is keyed by.
//
// socks5h is mapped onto socks5. net/http has accepted "socks5h" since Go 1.22 and treats the
// two the same, because its SOCKS dialer (socks_bundle.go, socksDialer.connect) never resolves
// the target itself: anything that is not an IP literal goes to the proxy as a domain name
// (ATYP 0x03). So "socks5" already resolves remotely, and the mapping keeps that true on any
// toolchain the module's go directive admits.
func ParseAccountProxy(raw string) (*url.URL, error) {
	u, ok := parseProxyURL(raw, func(scheme string) bool { return defaultProxyPorts[scheme] != "" })
	if !ok {
		return nil, ErrInvalidAccountProxy
	}
	if u.Scheme == "socks5" || u.Scheme == "socks5h" {
		// RFC 1929 caps both fields at 255 bytes; the stdlib dialer would refuse them only on
		// the first request, after the attempt was already spent.
		if u.User != nil {
			password, _ := u.User.Password()
			if len(u.User.Username()) > 255 || len(password) > 255 {
				return nil, ErrInvalidAccountProxy
			}
		}
		u.Scheme = "socks5"
	}
	if u.Port() == "" {
		u.Host = net.JoinHostPort(u.Hostname(), defaultProxyPorts[u.Scheme])
	}
	u.Path = ""
	return u, nil
}

// RedactProxy names a proxy for logs: scheme and host:port, never the credentials. An
// unparseable URL is not echoed at all.
func RedactProxy(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return "<invalid proxy URL>"
	}
	return u.Scheme + "://" + u.Host
}

// maxAccountClients bounds the per-account transports kept around. Each one holds its own idle
// connection pool, so a proxy that stopped being used should not pin sockets forever.
const maxAccountClients = 64

type poolEntry struct {
	client   *http.Client
	lastUsed time.Time
}

// Pool hands out the upstream client for a candidate: the global one (ANTHROPIC_PROXY_URL, or
// direct when that is unset) for accounts without a proxy of their own, and one cached client
// per distinct account proxy URL otherwise. Safe for concurrent use.
type Pool struct {
	global *http.Client

	mu      sync.Mutex
	clients map[string]*poolEntry
}

// NewPool builds a pool whose default client goes through [globalProxy] (see AnthropicTransport).
func NewPool(globalProxy string) *Pool {
	return &Pool{global: newClient(AnthropicTransport(globalProxy)), clients: map[string]*poolEntry{}}
}

// newClient has no overall timeout (SSE streams run for minutes) and never follows redirects:
// the datapath relays upstream answers as they are.
func newClient(tr *http.Transport) *http.Client {
	return &http.Client{
		Transport:     tr,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// Client returns the client for an account's proxy URL; "" means the global client. An invalid
// URL is an error — never a silent fallback to the global route or to direct networking.
func (p *Pool) Client(proxyURL string) (*http.Client, error) {
	if proxyURL == "" {
		return p.global, nil
	}
	now := time.Now()
	p.mu.Lock()
	defer p.mu.Unlock()
	if e, ok := p.clients[proxyURL]; ok {
		e.lastUsed = now
		return e.client, nil
	}
	u, err := ParseAccountProxy(proxyURL)
	if err != nil {
		return nil, err
	}
	if len(p.clients) >= maxAccountClients {
		p.evictOldestLocked()
	}
	c := newClient(newTransport(http.ProxyURL(u)))
	p.clients[proxyURL] = &poolEntry{client: c, lastUsed: now}
	return c, nil
}

// evictOldestLocked drops the least recently used client. In-flight requests on it finish
// normally — CloseIdleConnections only closes sockets nobody is using.
func (p *Pool) evictOldestLocked() {
	var oldestKey string
	var oldest *poolEntry
	for k, e := range p.clients {
		if oldest == nil || e.lastUsed.Before(oldest.lastUsed) {
			oldestKey, oldest = k, e
		}
	}
	if oldest != nil {
		delete(p.clients, oldestKey)
		oldest.client.Transport.(*http.Transport).CloseIdleConnections()
	}
}
