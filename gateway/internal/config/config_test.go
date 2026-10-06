package config

import "testing"

func TestLoadDefaults(t *testing.T) {
	t.Setenv("PORT", "")
	t.Setenv("SERVICE_URL", "")
	t.Setenv("UPSTREAM_BASE_URL", "")
	t.Setenv("INTERNAL_TOKEN", "")
	c := Load()
	if c.Port != "9000" {
		t.Errorf("Port default = %q, want 9000", c.Port)
	}
	if c.ServiceURL != "http://service:8787" {
		t.Errorf("ServiceURL default = %q", c.ServiceURL)
	}
	if c.UpstreamBaseURL != "https://api.anthropic.com" {
		t.Errorf("UpstreamBaseURL default = %q", c.UpstreamBaseURL)
	}
	if c.InternalToken != "" {
		t.Errorf("InternalToken = %q, want empty", c.InternalToken)
	}
}

func TestLoadOverrides(t *testing.T) {
	t.Setenv("PORT", "9100")
	t.Setenv("SERVICE_URL", "http://localhost:8787")
	t.Setenv("UPSTREAM_BASE_URL", "https://example.test")
	t.Setenv("INTERNAL_TOKEN", "sekret")
	c := Load()
	if c.Port != "9100" {
		t.Errorf("Port = %q, want 9100", c.Port)
	}
	if c.ServiceURL != "http://localhost:8787" {
		t.Errorf("ServiceURL = %q", c.ServiceURL)
	}
	if c.UpstreamBaseURL != "https://example.test" {
		t.Errorf("UpstreamBaseURL = %q", c.UpstreamBaseURL)
	}
	if c.InternalToken != "sekret" {
		t.Errorf("InternalToken = %q", c.InternalToken)
	}
}

func TestDedicatedAnthropicProxyConfig(t *testing.T) {
	t.Setenv("ANTHROPIC_PROXY_URL", "http://proxy.internal:3128")
	if Load().AnthropicProxyURL != "http://proxy.internal:3128" {
		t.Fatal("dedicated proxy override missing")
	}
	t.Setenv("ANTHROPIC_PROXY_URL", "")
	t.Setenv("HTTPS_PROXY", "http://other.internal:8080")
	if Load().AnthropicProxyURL != "" {
		t.Fatal("generic proxy env unexpectedly used")
	}
}
