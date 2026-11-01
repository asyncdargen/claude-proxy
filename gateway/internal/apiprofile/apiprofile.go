// Package apiprofile shapes a request bound for an API-key account into the product surface the
// account is configured for. The key decides the Console workspace that pays; what the request
// *looks like* decides which product it is billed as. Claude's included API credits exclude
// interactive Claude Code requests but cover the Agent SDK, and a plain API key may want neither
// identity. Subscription (OAuth) accounts are never touched here.
package apiprofile

import (
	"bytes"
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
)

// Modes, as the service sends them in control.Candidate.APIKeyMode. "" means ModeClaudeCode.
const (
	ModeAnthropicAPI = "ANTHROPIC_API"
	ModeAgentSDK     = "AGENT_SDK"
	ModeClaudeCode   = "CLAUDE_CODE"
)

const (
	ccSentence  = "You are Claude Code, Anthropic's official CLI for Claude."
	sdkSentence = "You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK."
	// ccMarker is shared by both sentences: a system prompt carrying it already has an identity.
	ccMarker      = "You are Claude Code, Anthropic's official CLI for Claude"
	billingPrefix = "x-anthropic-billing-header:"

	betaOAuth      = "oauth-2025-04-20"
	betaClaudeCode = "claude-code-20250219"

	// neutralUserAgent stands in for the CLI's user agent on a plain API request.
	neutralUserAgent = "claude-proxy/1.0"
	// defaultCLIVersion is used when the client's user agent names no claude-cli version.
	defaultCLIVersion = "2.1.292"
	agentSDKSuffix    = " (external, sdk-ts, agent-sdk/0.3.310)"
)

var cliVersionRe = regexp.MustCompile(`^claude-cli/([^ ]+)`)

// Apply rewrites headers in place and returns the (possibly rewritten) body for one upstream
// attempt. [path] is the upstream path; the body is only edited on Messages endpoints
// (/v1/messages and /v1/messages/count_tokens), and nothing is ever added to metadata. When the
// body needs no change, the very same slice comes back — no re-marshal.
func Apply(headers http.Header, body []byte, accountType, mode, path string) []byte {
	if accountType != "API_KEY" {
		return body
	}
	switch mode {
	case ModeAnthropicAPI:
		setBetas(headers, nil, betaOAuth, betaClaudeCode)
		headers.Del("x-app")
		headers.Del("X-Claude-Code-Session-Id")
		headers.Del("anthropic-dangerous-direct-browser-access")
		headers.Set("User-Agent", neutralUserAgent)
		if isMessages(path) {
			return editBody(body, anthropicAPISystem, true)
		}
	case ModeAgentSDK:
		version := defaultCLIVersion
		if m := cliVersionRe.FindStringSubmatch(headers.Get("User-Agent")); m != nil {
			version = m[1]
		}
		headers.Set("User-Agent", "claude-cli/"+version+agentSDKSuffix)
		headers.Set("x-app", "cli")
		setBetas(headers, []string{betaClaudeCode}, betaOAuth)
		if isMessages(path) {
			return editBody(body, agentSDKSystem, false)
		}
	}
	return body
}

func isMessages(path string) bool {
	path, _, _ = strings.Cut(path, "?")
	return path == "/v1/messages" || strings.HasPrefix(path, "/v1/messages/")
}

// setBetas rewrites anthropic-beta — comma-joined and multi-value forms alike — into one
// comma-joined value without [drop] and with [ensure] appended when missing. Client betas keep
// their order.
func setBetas(headers http.Header, ensure []string, drop ...string) {
	var kept []string
	seen := map[string]bool{}
	for _, value := range headers.Values("anthropic-beta") {
		for _, beta := range strings.Split(value, ",") {
			beta = strings.TrimSpace(beta)
			key := strings.ToLower(beta)
			if beta == "" || seen[key] || contains(drop, key) {
				continue
			}
			seen[key] = true
			kept = append(kept, beta)
		}
	}
	for _, beta := range ensure {
		if !seen[beta] {
			seen[beta] = true
			kept = append(kept, beta)
		}
	}
	headers.Del("anthropic-beta")
	if len(kept) > 0 {
		headers.Set("anthropic-beta", strings.Join(kept, ","))
	}
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

// systemText edits one piece of system text. ok=false drops the piece entirely.
type systemText func(string) (out string, keep bool)

// dropBillingLines removes every line that is the CLI's billing attribution header. The whole
// line goes, not just the prefix: what follows the colon is the attribution itself.
func dropBillingLines(text string) string {
	if !strings.Contains(text, billingPrefix) {
		return text
	}
	lines := strings.Split(text, "\n")
	kept := lines[:0]
	for _, line := range lines {
		if !strings.HasPrefix(strings.TrimSpace(line), billingPrefix) {
			kept = append(kept, line)
		}
	}
	return strings.Join(kept, "\n")
}

// anthropicAPISystem strips both Claude Code identities (the CLI's and the Agent SDK's) and the
// billing line. A line left blank by the removal goes with it, and a piece left empty is dropped.
func anthropicAPISystem(text string) (string, bool) {
	out := dropBillingLines(text)
	if strings.Contains(out, ccMarker) {
		lines := strings.Split(out, "\n")
		kept := lines[:0]
		for _, line := range lines {
			stripped := strings.ReplaceAll(strings.ReplaceAll(line, sdkSentence, ""), ccSentence, "")
			if stripped != line && strings.TrimSpace(stripped) == "" {
				continue
			}
			kept = append(kept, stripped)
		}
		out = strings.TrimLeft(strings.Join(kept, "\n"), "\n")
	}
	if out == text {
		return text, true
	}
	return out, strings.TrimSpace(out) != ""
}

// agentSDKSystem swaps the CLI sentence for the Agent SDK's in place — the rest of the text stays
// — and drops the billing line. The SDK sentence does not contain the CLI one ("Claude," vs
// "Claude."), so applying it again finds nothing to replace.
func agentSDKSystem(text string) (string, bool) {
	out := strings.ReplaceAll(dropBillingLines(text), ccSentence, sdkSentence)
	if out == text {
		return text, true
	}
	return out, strings.TrimSpace(out) != ""
}

// editBody applies [edit] to every piece of system text and, for a plain API request, drops the
// Claude Code identity from metadata. The Agent SDK profile also guarantees its sentence is
// present, prepending it as the first system block when no identity is there.
func editBody(body []byte, edit systemText, stripIdentity bool) []byte {
	var root map[string]json.RawMessage
	if len(body) == 0 || json.Unmarshal(body, &root) != nil || root == nil {
		return body
	}
	changed := false

	raw, hasSystem := root["system"]
	system, sysChanged, hasIdentity := editSystem(raw, hasSystem, edit)
	if !stripIdentity && !hasIdentity {
		system = prependBlock(system, sdkSentence)
		sysChanged = true
	}
	if sysChanged {
		changed = true
		if system == nil {
			delete(root, "system")
		} else {
			root["system"] = system
		}
	}

	if stripIdentity {
		if meta, ok := stripUserIDIdentity(root["metadata"]); ok {
			changed = true
			if meta == nil {
				delete(root, "metadata")
			} else {
				root["metadata"] = meta
			}
		}
	}
	if !changed {
		return body
	}
	out, err := marshal(root)
	if err != nil {
		return body
	}
	return out
}

// editSystem applies [edit] to a string or block-array system. It returns the new value (nil =
// remove the field), whether anything changed, and whether the result still carries a Claude
// Code identity sentence. Shapes it does not understand are left alone.
func editSystem(raw json.RawMessage, present bool, edit systemText) (json.RawMessage, bool, bool) {
	if !present || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return nil, false, false
	}
	var text string
	if json.Unmarshal(raw, &text) == nil {
		out, keep := edit(text)
		if !keep {
			return nil, true, false
		}
		if out == text {
			return raw, false, strings.Contains(text, ccMarker)
		}
		enc, err := marshal(out)
		if err != nil {
			return raw, false, strings.Contains(text, ccMarker)
		}
		return enc, true, strings.Contains(out, ccMarker)
	}
	var blocks []json.RawMessage
	if json.Unmarshal(raw, &blocks) != nil {
		return raw, false, true // unknown shape: never prepend into it either
	}
	changed, identity := false, false
	kept := make([]json.RawMessage, 0, len(blocks))
	for _, block := range blocks {
		out, keep, blockChanged, blockIdentity := editBlock(block, edit)
		identity = identity || blockIdentity
		if blockChanged {
			changed = true
		}
		if keep {
			kept = append(kept, out)
		}
	}
	if !changed {
		return raw, false, identity
	}
	if len(kept) == 0 {
		return nil, true, identity
	}
	enc, err := marshal(kept)
	if err != nil {
		return raw, false, identity
	}
	return enc, true, identity
}

// editBlock edits one system element: a text block (only its "text" changes; cache_control and
// any other key survive) or a bare string. Anything else passes through untouched.
func editBlock(block json.RawMessage, edit systemText) (out json.RawMessage, keep, changed, identity bool) {
	var bare string
	if json.Unmarshal(block, &bare) == nil {
		text, ok := edit(bare)
		if !ok {
			return nil, false, true, false
		}
		if text == bare {
			return block, true, false, strings.Contains(bare, ccMarker)
		}
		enc, err := marshal(text)
		if err != nil {
			return block, true, false, strings.Contains(bare, ccMarker)
		}
		return enc, true, true, strings.Contains(text, ccMarker)
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(block, &fields) != nil {
		return block, true, false, false
	}
	var text string
	if json.Unmarshal(fields["text"], &text) != nil {
		return block, true, false, false
	}
	edited, ok := edit(text)
	if !ok {
		return nil, false, true, false
	}
	if edited == text {
		return block, true, false, strings.Contains(text, ccMarker)
	}
	encText, err := marshal(edited)
	if err != nil {
		return block, true, false, strings.Contains(text, ccMarker)
	}
	fields["text"] = encText
	enc, err := marshal(fields)
	if err != nil {
		return block, true, false, strings.Contains(text, ccMarker)
	}
	return enc, true, true, strings.Contains(edited, ccMarker)
}

// prependBlock puts a text block in front of the system content; a string system becomes its
// second block, which is how the Agent SDK itself sends a custom system prompt.
func prependBlock(system json.RawMessage, text string) json.RawMessage {
	first, _ := marshal(map[string]string{"type": "text", "text": text})
	blocks := []json.RawMessage{first}
	if system != nil {
		var s string
		var arr []json.RawMessage
		switch {
		case json.Unmarshal(system, &s) == nil:
			if s != "" {
				rest, _ := marshal(map[string]string{"type": "text", "text": s})
				blocks = append(blocks, rest)
			}
		case json.Unmarshal(system, &arr) == nil:
			blocks = append(blocks, arr...)
		}
	}
	out, _ := marshal(blocks)
	return out
}

// stripUserIDIdentity removes metadata.user_id when it is Claude Code's identity blob (escaped
// JSON naming a device, account or session). A plain user id string is the API's own feature and
// stays. ok reports a change; a nil result means metadata is now empty and should go.
func stripUserIDIdentity(raw json.RawMessage) (json.RawMessage, bool) {
	if raw == nil {
		return nil, false
	}
	var meta map[string]json.RawMessage
	if json.Unmarshal(raw, &meta) != nil || meta == nil {
		return nil, false
	}
	var encoded string
	if json.Unmarshal(meta["user_id"], &encoded) != nil {
		return nil, false
	}
	var identity map[string]json.RawMessage
	if json.Unmarshal([]byte(encoded), &identity) != nil {
		return nil, false
	}
	_, dev := identity["device_id"]
	_, acc := identity["account_uuid"]
	_, sid := identity["session_id"]
	if !dev && !acc && !sid {
		return nil, false
	}
	delete(meta, "user_id")
	if len(meta) == 0 {
		return nil, true
	}
	out, err := marshal(meta)
	if err != nil {
		return nil, false
	}
	return out, true
}

// marshal is json.Marshal without HTML escaping: transcript text full of <, > and & must reach
// upstream as written, not rewritten to \u003c and friends.
func marshal(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}
