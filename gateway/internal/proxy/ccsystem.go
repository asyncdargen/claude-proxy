package proxy

import (
	"bytes"
	"strings"

	"claudeproxy/gateway/internal/ccident"
)

// ccPromptMarker is the part of the Claude Code system prompt every Claude Code build sends
// (the Agent SDK appends ", running within the Claude Agent SDK." to the same sentence).
var ccPromptMarker = []byte("You are Claude Code, Anthropic's official CLI for Claude")

// ensureClaudeCodeSystem adds the Claude Code system block to a Messages request bound for a
// subscription (OAuth) account when the client did not send it. Subscription accounts answer such
// requests with a bare 429 "Error" (no rate-limit headers) for every current model — the Claude
// desktop app's gateway check and other non-Claude-Code clients hit exactly that. Requests that
// already carry the prompt anywhere are returned byte-identical, so real Claude Code traffic and
// its prompt-cache prefix are untouched. API-key accounts need no identity and are left alone.
func ensureClaudeCodeSystem(body []byte, accountType, path string) []byte {
	if len(body) == 0 || accountType == "API_KEY" || !strings.HasPrefix(path, "/v1/messages") {
		return body
	}
	if bytes.Contains(body, ccPromptMarker) {
		return body
	}
	return ccident.InjectSystemPrompt(body)
}
