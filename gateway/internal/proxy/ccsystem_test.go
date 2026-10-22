package proxy

import (
	"encoding/json"
	"testing"

	"claudeproxy/gateway/internal/ccident"
)

func firstSystemText(t *testing.T, body []byte) (string, int) {
	t.Helper()
	var obj struct {
		System []struct {
			Text string `json:"text"`
		} `json:"system"`
	}
	if err := json.Unmarshal(body, &obj); err != nil {
		t.Fatalf("bad json: %v: %s", err, body)
	}
	if len(obj.System) == 0 {
		return "", 0
	}
	return obj.System[0].Text, len(obj.System)
}

func TestEnsureCCSystemAddsPromptForBareOAuthRequest(t *testing.T) {
	out := ensureClaudeCodeSystem([]byte(`{"model":"claude-haiku-5-5","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}`), "OAUTH", "/v1/messages")
	if txt, n := firstSystemText(t, out); txt != ccident.SystemPrompt || n != 1 {
		t.Fatalf("system = %q (%d blocks), want the Claude Code block", txt, n)
	}
}

func TestEnsureCCSystemKeepsClientSystemAfterPrompt(t *testing.T) {
	out := ensureClaudeCodeSystem([]byte(`{"system":"Be terse.","messages":[]}`), "OAUTH_STATIC", "/v1/messages")
	if txt, n := firstSystemText(t, out); txt != ccident.SystemPrompt || n != 2 {
		t.Fatalf("system = %q (%d blocks)", txt, n)
	}
}

// Real Claude Code (and the Agent SDK variant, possibly after a billing-header block) must pass
// byte-identical: rewriting would shift its prompt-cache prefix.
func TestEnsureCCSystemLeavesClaudeCodeTrafficUntouched(t *testing.T) {
	for _, in := range []string{
		`{"system":[{"type":"text","text":"x-anthropic-billing-header: cc_version=2.1"},{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude.","cache_control":{"type":"ephemeral"}}],"messages":[]}`,
		`{"system":[{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK."}],"messages":[]}`,
	} {
		if out := ensureClaudeCodeSystem([]byte(in), "OAUTH", "/v1/messages"); string(out) != in {
			t.Fatalf("body changed:\n in=%s\nout=%s", in, out)
		}
	}
}

func TestEnsureCCSystemSkipsAPIKeysAndOtherPaths(t *testing.T) {
	in := `{"messages":[]}`
	if out := ensureClaudeCodeSystem([]byte(in), "API_KEY", "/v1/messages"); string(out) != in {
		t.Fatalf("API key body changed: %s", out)
	}
	if out := ensureClaudeCodeSystem([]byte(in), "OAUTH", "/v1/models"); string(out) != in {
		t.Fatalf("non-messages body changed: %s", out)
	}
	if out := ensureClaudeCodeSystem(nil, "OAUTH", "/v1/messages"); out != nil {
		t.Fatalf("empty body changed")
	}
}
