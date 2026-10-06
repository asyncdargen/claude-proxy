// Package config loads the gateway's runtime configuration from environment variables.
package config

import (
	"os"
	"strconv"
	"time"
)

// Config is the gateway's runtime configuration.
type Config struct {
	// Port the gateway listens on (env PORT, default "9000").
	Port string
	// ServiceURL is the base URL of the Kotlin service's control API (env SERVICE_URL).
	ServiceURL string
	// InternalToken is the shared secret sent as X-Internal-Token to the control API.
	InternalToken string
	// UpstreamBaseURL is the Anthropic API base (env UPSTREAM_BASE_URL).
	UpstreamBaseURL string
	// AnthropicProxyURL forces Anthropic traffic through an HTTP CONNECT proxy.
	AnthropicProxyURL string
	// UpstreamStallTimeout is how long an open stream may go without a single upstream byte
	// before the relay gives up on it (env UPSTREAM_STALL_SECONDS, 0 disables the watchdog).
	// Our keep-alive comments make a dead upstream look alive to the client, so without this a
	// stream that stops mid-answer hangs until the *user* gives up minutes later.
	UpstreamStallTimeout time.Duration
	// EarlyHeadTimeout is how long a streaming request may wait for Anthropic's response head
	// before we open the client's stream ourselves and start sending keep-alives
	// (env EARLY_HEAD_SECONDS, 0 disables it). Until the head is out the client sees zero bytes,
	// and anything in front of us counts that silence: Cloudflare cuts a request off at 120s
	// with a 524, which is what a slow upstream (or a couple of retried candidates) reached.
	EarlyHeadTimeout time.Duration
	// ClaudeCodeUserAgent is the user agent the routing gateways present upstream
	// (env CLAUDE_CODE_USER_AGENT). The proxy datapath forwards the real client's own.
	ClaudeCodeUserAgent string
}

// Load reads the configuration from the environment, applying defaults.
func Load() *Config {
	return &Config{
		Port:                 envOr("PORT", "9000"),
		ServiceURL:           envOr("SERVICE_URL", "http://service:8787"),
		InternalToken:        os.Getenv("INTERNAL_TOKEN"),
		AnthropicProxyURL:    os.Getenv("ANTHROPIC_PROXY_URL"),
		UpstreamBaseURL:      envOr("UPSTREAM_BASE_URL", "https://api.anthropic.com"),
		UpstreamStallTimeout: secondsOr("UPSTREAM_STALL_SECONDS", 120*time.Second),
		EarlyHeadTimeout:     secondsOr("EARLY_HEAD_SECONDS", 45*time.Second),
		ClaudeCodeUserAgent:  envOr("CLAUDE_CODE_USER_AGENT", "claude-cli/2.1.259 (external, cli)"),
	}
}

// secondsOr reads a whole-seconds duration from the environment. An unparseable value keeps the
// default; an explicit 0 is honored and turns the setting off.
func secondsOr(key string, def time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 {
		return def
	}
	return time.Duration(n) * time.Second
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
