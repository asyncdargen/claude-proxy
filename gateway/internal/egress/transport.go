// Package egress configures only Anthropic's external HTTP transport. Internal control
// and native OpenAI clients deliberately do not use this proxy.
package egress

import (
	"errors"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// ParseProxy validates operator configuration without including credentials in errors.
// Only HTTP CONNECT proxies are supported, consistently with the service's CIO client.
func ParseProxy(raw string) (*url.URL, error) {
	if raw == "" {
		return nil, nil
	}
	invalid := errors.New("ANTHROPIC_PROXY_URL must be an http:// proxy URL with a valid host/port and no path, query or fragment")
	u, ok := parseProxyURL(raw, func(scheme string) bool { return scheme == "http" })
	if !ok {
		return nil, invalid
	}
	return u, nil
}

// parseProxyURL is the shared shape check for the global and the per-account proxy. It never
// returns url.Parse's own error: that one quotes the whole URL, credentials included.
func parseProxyURL(raw string, schemeOK func(string) bool) (*url.URL, bool) {
	u, err := url.Parse(raw)
	if err != nil || !schemeOK(u.Scheme) || u.Hostname() == "" || u.Opaque != "" || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
		return nil, false
	}
	if u.Port() != "" {
		port, err := strconv.Atoi(u.Port())
		if err != nil || port < 1 || port > 65535 {
			return nil, false
		}
	} else if len(u.Host) > 0 && u.Host[len(u.Host)-1] == ':' {
		return nil, false
	}
	if u.User != nil {
		username := u.User.Username()
		password, _ := u.User.Password()
		if username == "" || strings.Contains(username, ":") || strings.ContainsAny(username+password, "\r\n\x00") {
			return nil, false
		}
	}
	return u, true
}

// newTransport is the one Anthropic transport shape, whichever proxy it goes through. Proxy is
// a fixed function, never http.ProxyFromEnvironment: HTTP(S)_PROXY/NO_PROXY must neither bypass
// a configured proxy nor route traffic through one nobody configured for Anthropic.
func newTransport(proxy func(*http.Request) (*url.URL, error)) *http.Transport {
	return &http.Transport{
		Proxy:                 proxy,
		DialContext:           (&net.Dialer{Timeout: 10 * time.Second}).DialContext,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: time.Second,
		MaxIdleConns:          100,
		IdleConnTimeout:       90 * time.Second,
		ForceAttemptHTTP2:     true,
	}
}

// AnthropicTransport never falls back to direct networking when a configured proxy fails.
// It ignores HTTP(S)_PROXY/NO_PROXY to avoid accidental bypass or routing internal services.
func AnthropicTransport(raw string) *http.Transport {
	u, err := ParseProxy(raw)
	return newTransport(func(*http.Request) (*url.URL, error) { return u, err })
}
