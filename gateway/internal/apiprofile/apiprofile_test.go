package apiprofile

import (
	"bytes"
	"encoding/json"
	"net/http"
	"reflect"
	"strings"
	"testing"
)

const (
	identityMeta = `{"user_id":"{\"device_id\":\"dev\",\"account_uuid\":\"\",\"session_id\":\"sid\"}"}`
	billingLine  = "x-anthropic-billing-header: cc_version=2.1.292.abc; cc_entrypoint=cli;"
)

func ccHeaders() http.Header {
	h := http.Header{}
	h.Set("User-Agent", "claude-cli/2.1.300 (external, cli)")
	h.Set("x-app", "cli")
	h.Set("X-Claude-Code-Session-Id", "sid")
	h.Set("anthropic-dangerous-direct-browser-access", "true")
	h.Set("x-api-key", "sk-ant-api-test")
	// Both header forms at once: a comma-joined value and a second header line.
	h.Add("anthropic-beta", "claude-code-20250219, oauth-2025-04-20,context-management-2025-06-27")
	h.Add("anthropic-beta", "interleaved-thinking-2025-05-14")
	return h
}

func decode(t *testing.T, body []byte) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("output is not JSON: %v\n%s", err, body)
	}
	return m
}

func TestHeaders(t *testing.T) {
	for _, tc := range []struct {
		name, accountType, mode string
		ua, xApp, session       string
		beta                    []string
	}{
		{"oauth untouched", "OAUTH", ModeAnthropicAPI, "claude-cli/2.1.300 (external, cli)", "cli", "sid",
			[]string{"claude-code-20250219, oauth-2025-04-20,context-management-2025-06-27", "interleaved-thinking-2025-05-14"}},
		{"api key claude code untouched", "API_KEY", ModeClaudeCode, "claude-cli/2.1.300 (external, cli)", "cli", "sid",
			[]string{"claude-code-20250219, oauth-2025-04-20,context-management-2025-06-27", "interleaved-thinking-2025-05-14"}},
		{"api key empty mode untouched", "API_KEY", "", "claude-cli/2.1.300 (external, cli)", "cli", "sid",
			[]string{"claude-code-20250219, oauth-2025-04-20,context-management-2025-06-27", "interleaved-thinking-2025-05-14"}},
		{"anthropic api", "API_KEY", ModeAnthropicAPI, neutralUserAgent, "", "",
			[]string{"context-management-2025-06-27,interleaved-thinking-2025-05-14"}},
		{"agent sdk", "API_KEY", ModeAgentSDK, "claude-cli/2.1.300 (external, sdk-ts, agent-sdk/0.3.310)", "cli", "sid",
			[]string{"claude-code-20250219,context-management-2025-06-27,interleaved-thinking-2025-05-14"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := ccHeaders()
			Apply(h, nil, tc.accountType, tc.mode, "/v1/messages")
			if got := h.Get("User-Agent"); got != tc.ua {
				t.Errorf("User-Agent = %q, want %q", got, tc.ua)
			}
			if got := h.Get("x-app"); got != tc.xApp {
				t.Errorf("x-app = %q, want %q", got, tc.xApp)
			}
			if got := h.Get("X-Claude-Code-Session-Id"); got != tc.session {
				t.Errorf("session header = %q, want %q", got, tc.session)
			}
			if got := h.Values("anthropic-beta"); !reflect.DeepEqual(got, tc.beta) {
				t.Errorf("anthropic-beta = %q, want %q", got, tc.beta)
			}
			if h.Get("x-api-key") != "sk-ant-api-test" {
				t.Error("credentials must never be touched")
			}
			if tc.mode == ModeAnthropicAPI && tc.accountType == "API_KEY" && h.Get("anthropic-dangerous-direct-browser-access") != "" {
				t.Error("browser-access header survived")
			}
		})
	}
}

func TestAgentSDKHeaderEdgeCases(t *testing.T) {
	h := http.Header{}
	h.Set("User-Agent", "python-httpx/0.27")
	Apply(h, nil, "API_KEY", ModeAgentSDK, "/v1/messages")
	if got := h.Get("User-Agent"); got != "claude-cli/"+defaultCLIVersion+agentSDKSuffix {
		t.Errorf("default version UA = %q", got)
	}
	if got := h.Values("anthropic-beta"); !reflect.DeepEqual(got, []string{betaClaudeCode}) {
		t.Errorf("claude-code beta not ensured: %q", got)
	}
	// Only a dropped beta and nothing else: the header goes away instead of staying empty.
	h = http.Header{"Anthropic-Beta": {"oauth-2025-04-20,claude-code-20250219"}}
	Apply(h, nil, "API_KEY", ModeAnthropicAPI, "/v1/messages")
	if _, ok := h["Anthropic-Beta"]; ok {
		t.Errorf("empty anthropic-beta left behind: %q", h.Values("anthropic-beta"))
	}
}

func TestSystemAnthropicAPI(t *testing.T) {
	for _, tc := range []struct {
		name, in string
		system   any // expected "system" after decoding; nil = absent
	}{
		{"string: sentence and billing line removed, rest kept",
			`{"system":"` + billingLine + `\nYou are Claude Code, Anthropic's official CLI for Claude.\n\nBe terse <b>&</b>."}`,
			"Be terse <b>&</b>."},
		{"string: sentence inside a line keeps the line",
			`{"system":"Hello. You are Claude Code, Anthropic's official CLI for Claude. Bye."}`,
			"Hello.  Bye."},
		{"string: only the sentence drops system",
			`{"system":"You are Claude Code, Anthropic's official CLI for Claude."}`, nil},
		{"string: agent sdk sentence is an identity too",
			`{"system":"You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK.\nRest"}`,
			"Rest"},
		{"blocks: cc and billing blocks dropped, cache_control kept",
			`{"system":[{"type":"text","text":"` + billingLine + `"},{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude."},{"type":"text","text":"Project rules","cache_control":{"type":"ephemeral","ttl":"1h"}}]}`,
			[]any{map[string]any{"type": "text", "text": "Project rules", "cache_control": map[string]any{"type": "ephemeral", "ttl": "1h"}}}},
		{"blocks: mixed block keeps its other text and keys",
			`{"system":[{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude.\nYou help with Go.","cache_control":{"type":"ephemeral"}}]}`,
			[]any{map[string]any{"type": "text", "text": "You help with Go.", "cache_control": map[string]any{"type": "ephemeral"}}}},
		{"blocks: all identity drops system",
			`{"system":[{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude."}]}`, nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			out := Apply(http.Header{}, []byte(tc.in), "API_KEY", ModeAnthropicAPI, "/v1/messages")
			got, ok := decode(t, out)["system"]
			if tc.system == nil {
				if ok {
					t.Fatalf("system should be gone, got %#v", got)
				}
				return
			}
			if !reflect.DeepEqual(got, tc.system) {
				t.Fatalf("system = %#v\nwant     %#v", got, tc.system)
			}
		})
	}
}

func TestSystemAgentSDK(t *testing.T) {
	sdk := map[string]any{"type": "text", "text": sdkSentence}
	for _, tc := range []struct {
		name, in string
		system   any
	}{
		{"string: sentence replaced in place, billing line dropped",
			`{"system":"` + billingLine + `\nYou are Claude Code, Anthropic's official CLI for Claude.\nRules"}`,
			sdkSentence + "\nRules"},
		{"string without identity becomes blocks behind the sdk sentence",
			`{"system":"Rules"}`,
			[]any{sdk, map[string]any{"type": "text", "text": "Rules"}}},
		{"absent system gets the sdk block", `{"model":"m"}`, []any{sdk}},
		{"null system gets the sdk block", `{"system":null}`, []any{sdk}},
		{"only a billing line leaves just the sdk block", `{"system":"` + billingLine + `"}`, []any{sdk}},
		{"blocks: only the sentence is replaced, rest and cache_control kept",
			`{"system":[{"type":"text","text":"` + billingLine + `"},{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude.\n\nYou help with Go.","cache_control":{"type":"ephemeral"}}]}`,
			[]any{map[string]any{"type": "text", "text": sdkSentence + "\n\nYou help with Go.", "cache_control": map[string]any{"type": "ephemeral"}}}},
		{"blocks without identity get the sdk block first",
			`{"system":[{"type":"text","text":"Rules","cache_control":{"type":"ephemeral"}}]}`,
			[]any{sdk, map[string]any{"type": "text", "text": "Rules", "cache_control": map[string]any{"type": "ephemeral"}}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			out := Apply(http.Header{}, []byte(tc.in), "API_KEY", ModeAgentSDK, "/v1/messages")
			if got := decode(t, out)["system"]; !reflect.DeepEqual(got, tc.system) {
				t.Fatalf("system = %#v\nwant     %#v", got, tc.system)
			}
		})
	}
}

func TestMetadata(t *testing.T) {
	for _, tc := range []struct {
		name, mode, in string
		meta           any
	}{
		{"anthropic api drops the identity blob", ModeAnthropicAPI, `{"metadata":` + identityMeta + `}`, nil},
		{"anthropic api keeps other metadata keys", ModeAnthropicAPI,
			`{"metadata":{"user_id":"{\"session_id\":\"s\"}","tier":"x"}}`, map[string]any{"tier": "x"}},
		{"anthropic api keeps a plain user id", ModeAnthropicAPI, `{"metadata":{"user_id":"customer-42"}}`,
			map[string]any{"user_id": "customer-42"}},
		{"anthropic api keeps a json user id that is no identity", ModeAnthropicAPI, `{"metadata":{"user_id":"{\"org\":1}"}}`,
			map[string]any{"user_id": `{"org":1}`}},
		{"agent sdk keeps the identity blob", ModeAgentSDK, `{"system":"` + sdkSentence + `","metadata":` + identityMeta + `}`,
			map[string]any{"user_id": `{"device_id":"dev","account_uuid":"","session_id":"sid"}`}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			out := Apply(http.Header{}, []byte(tc.in), "API_KEY", tc.mode, "/v1/messages")
			got, ok := decode(t, out)["metadata"]
			if tc.meta == nil {
				if ok {
					t.Fatalf("metadata should be gone, got %#v", got)
				}
				return
			}
			if !reflect.DeepEqual(got, tc.meta) {
				t.Fatalf("metadata = %#v, want %#v", got, tc.meta)
			}
		})
	}
}

func TestBodyUnchangedReturnsSameBytes(t *testing.T) {
	cc := `{"system":"You are Claude Code, Anthropic's official CLI for Claude.","metadata":` + identityMeta + `}`
	for _, tc := range []struct {
		name, accountType, mode, path, body string
	}{
		{"oauth", "OAUTH", ModeAnthropicAPI, "/v1/messages", cc},
		{"claude code key", "API_KEY", ModeClaudeCode, "/v1/messages", cc},
		{"empty mode", "API_KEY", "", "/v1/messages", cc},
		{"anthropic api: nothing to strip", "API_KEY", ModeAnthropicAPI, "/v1/messages",
			`{ "model" : "m",  "system":[{"type":"text","text":"Mine"}], "messages":[{"role":"user","content":"<x> & y"}]}`},
		{"agent sdk: already in sdk shape", "API_KEY", ModeAgentSDK, "/v1/messages",
			`{ "model":"m", "system":[{"type":"text","text":"` + sdkSentence + `"}], "messages":[]}`},
		{"non-messages path", "API_KEY", ModeAnthropicAPI, "/v1/models", cc},
		{"invalid json passes through", "API_KEY", ModeAnthropicAPI, "/v1/messages", `{"system":"You are Claude Code, Anthropic's official CLI for Claude."`},
		{"invalid json passes through (sdk)", "API_KEY", ModeAgentSDK, "/v1/messages", `not json`},
		{"array body passes through", "API_KEY", ModeAgentSDK, "/v1/messages", `[1,2]`},
		{"empty body", "API_KEY", ModeAgentSDK, "/v1/messages", ``},
	} {
		t.Run(tc.name, func(t *testing.T) {
			in := []byte(tc.body)
			out := Apply(http.Header{}, in, tc.accountType, tc.mode, tc.path)
			if len(in) > 0 && &out[0] != &in[0] || !bytes.Equal(out, in) {
				t.Fatalf("body was rewritten:\n in %s\nout %s", in, out)
			}
		})
	}
}

func TestIdempotent(t *testing.T) {
	bodies := []string{
		`{"system":"` + billingLine + `\nYou are Claude Code, Anthropic's official CLI for Claude.\nRules <a>","metadata":` + identityMeta + `}`,
		`{"system":[{"type":"text","text":"` + billingLine + `"},{"type":"text","text":"You are Claude Code, Anthropic's official CLI for Claude.\nRules","cache_control":{"type":"ephemeral"}}],"metadata":` + identityMeta + `}`,
		`{"system":"Rules"}`,
		`{"model":"m"}`,
		`{"system":"Note: ` + sdkSentence + ` and more"}`,
	}
	for _, mode := range []string{ModeAnthropicAPI, ModeAgentSDK} {
		for _, b := range bodies {
			once := Apply(http.Header{}, []byte(b), "API_KEY", mode, "/v1/messages")
			twice := Apply(http.Header{}, once, "API_KEY", mode, "/v1/messages")
			if !bytes.Equal(once, twice) {
				t.Errorf("%s not idempotent:\n once %s\ntwice %s", mode, once, twice)
			}
			if mode == ModeAgentSDK && strings.Count(string(once), sdkSentence) != 1 {
				t.Errorf("sdk sentence count != 1: %s", once)
			}
		}
	}
}

func TestNoHTMLEscapingAndCountTokens(t *testing.T) {
	in := `{"system":"You are Claude Code, Anthropic's official CLI for Claude.\nUse <tags> & stuff","messages":[{"role":"user","content":"a<b>&c"}]}`
	out := Apply(http.Header{}, []byte(in), "API_KEY", ModeAnthropicAPI, "/v1/messages/count_tokens?beta=true")
	if bytes.Contains(out, []byte(`\u003c`)) || bytes.Contains(out, []byte(`\u0026`)) {
		t.Fatalf("HTML-escaped output: %s", out)
	}
	if !bytes.Contains(out, []byte(`"a<b>&c"`)) || bytes.Contains(out, []byte("Claude Code")) {
		t.Fatalf("count_tokens body not profiled: %s", out)
	}
	// Agent SDK on count_tokens edits the system but never invents metadata.
	out = Apply(http.Header{}, []byte(`{"model":"m","messages":[]}`), "API_KEY", ModeAgentSDK, "/v1/messages/count_tokens")
	if m := decode(t, out); m["metadata"] != nil || m["system"] == nil {
		t.Fatalf("count_tokens sdk body: %s", out)
	}
}
