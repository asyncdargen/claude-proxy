package openaigw

import (
	"encoding/json"
	"strings"

	"claudeproxy/gateway/internal/ccident"
	"claudeproxy/gateway/internal/models"
)

// ---- OpenAI Chat Completions wire types (the subset we translate) ----

type chatRequest struct {
	Model               string          `json:"model"`
	Messages            []chatMessage   `json:"messages"`
	MaxTokens           *int            `json:"max_tokens"`
	MaxCompletionTokens *int            `json:"max_completion_tokens"`
	Temperature         *float64        `json:"temperature"`
	TopP                *float64        `json:"top_p"`
	Stop                json.RawMessage `json:"stop"`
	Stream              bool            `json:"stream"`
	StreamOptions       *struct {
		IncludeUsage bool `json:"include_usage"`
	} `json:"stream_options"`
	Tools      []chatTool      `json:"tools"`
	ToolChoice json.RawMessage `json:"tool_choice"`
}

type chatMessage struct {
	Role       string          `json:"role"`
	Content    json.RawMessage `json:"content"`
	ToolCalls  []toolCall      `json:"tool_calls,omitempty"`
	ToolCallID string          `json:"tool_call_id,omitempty"`
}

type toolCall struct {
	ID       string `json:"id"`
	Type     string `json:"type"`
	Function struct {
		Name      string `json:"name"`
		Arguments string `json:"arguments"`
	} `json:"function"`
}

type chatTool struct {
	Type     string `json:"type"`
	Function struct {
		Name        string          `json:"name"`
		Description string          `json:"description"`
		Parameters  json.RawMessage `json:"parameters"`
	} `json:"function"`
}

type contentPart struct {
	Type     string `json:"type"`
	Text     string `json:"text"`
	ImageURL *struct {
		URL string `json:"url"`
	} `json:"image_url"`
}

// ---- Anthropic Messages wire types (what we build) ----

type anthRequest struct {
	Model         string            `json:"model"`
	MaxTokens     int               `json:"max_tokens"`
	System        []json.RawMessage `json:"system,omitempty"`
	Messages      []anthMessage     `json:"messages"`
	Tools         []anthTool        `json:"tools,omitempty"`
	ToolChoice    json.RawMessage   `json:"tool_choice,omitempty"`
	Temperature   *float64          `json:"temperature,omitempty"`
	TopP          *float64          `json:"top_p,omitempty"`
	StopSequences []string          `json:"stop_sequences,omitempty"`
	Stream        bool              `json:"stream,omitempty"`
}

type anthMessage struct {
	Role    string            `json:"role"`
	Content []json.RawMessage `json:"content"`
}

type anthTool struct {
	Name        string          `json:"name"`
	Description string          `json:"description,omitempty"`
	InputSchema json.RawMessage `json:"input_schema"`
}

const defaultMaxTokens = 4096

// translateRequest converts an OpenAI Chat Completions request into an Anthropic Messages
// request, injecting the Claude Code system prompt. [model] is the resolved Claude model id.
func translateRequest(req *chatRequest, model string) ([]byte, error) {
	out := anthRequest{Model: model, Stream: req.Stream}

	out.MaxTokens = defaultMaxTokens
	if req.MaxTokens != nil && *req.MaxTokens > 0 {
		out.MaxTokens = *req.MaxTokens
	} else if req.MaxCompletionTokens != nil && *req.MaxCompletionTokens > 0 {
		out.MaxTokens = *req.MaxCompletionTokens
	}
	// OpenAI temperature is [0,2]; Anthropic rejects >1. Clamp so valid OpenAI requests survive.
	// Newer models reject sampling parameters outright, so there they are dropped instead.
	if models.SamplingParams(model) {
		out.Temperature = clampTemperature(req.Temperature)
		out.TopP = req.TopP
	}
	out.StopSequences = parseStop(req.Stop)

	var systemParts []string
	var msgs []anthMessage
	appendBlocks := func(role string, blocks []json.RawMessage) {
		if len(blocks) == 0 {
			return
		}
		if n := len(msgs); n > 0 && msgs[n-1].Role == role {
			msgs[n-1].Content = append(msgs[n-1].Content, blocks...)
			return
		}
		msgs = append(msgs, anthMessage{Role: role, Content: blocks})
	}

	for i := range req.Messages {
		m := &req.Messages[i]
		switch m.Role {
		case "system", "developer":
			if s := extractText(m.Content); s != "" {
				systemParts = append(systemParts, s)
			}
		case "user":
			appendBlocks("user", userContentBlocks(m.Content))
		case "assistant":
			var blocks []json.RawMessage
			if s := extractText(m.Content); s != "" {
				blocks = append(blocks, textBlock(s))
			}
			for _, tc := range m.ToolCalls {
				blocks = append(blocks, toolUseBlock(tc))
			}
			appendBlocks("assistant", blocks)
		case "tool":
			appendBlocks("user", []json.RawMessage{toolResultBlock(m.ToolCallID, extractText(m.Content))})
		}
	}
	out.Messages = msgs
	out.System = ccident.SystemBlocks(strings.Join(systemParts, "\n\n"))

	for _, t := range req.Tools {
		if t.Type != "" && t.Type != "function" {
			continue
		}
		schema := t.Function.Parameters
		if len(schema) == 0 {
			schema = json.RawMessage(`{"type":"object","properties":{}}`)
		}
		out.Tools = append(out.Tools, anthTool{
			Name:        t.Function.Name,
			Description: t.Function.Description,
			InputSchema: schema,
		})
	}
	out.ToolChoice = translateToolChoice(req.ToolChoice)
	if !models.ForcedToolChoice(model) && isForcedToolChoice(out.ToolChoice) {
		// The model would 400 the forced form; "auto" with tools on offer is the closest it takes.
		out.ToolChoice = json.RawMessage(`{"type":"auto"}`)
	}

	// Prompt-cache breakpoints. This datapath builds the Anthropic body from scratch, so unless we
	// set cache_control here no breakpoint ever reaches Anthropic and routed traffic can never hit
	// the cache. Render order is tools → system → messages, so one marker on the last system block
	// caches the whole stable prefix (tools + Claude Code identity + client system) — that prefix
	// repeats across every request from a client, giving cross-request reads. A second marker on
	// the tail of the conversation gives multi-turn clients incremental history reads. Writes are
	// billed at the ephemeral (5-minute TTL) rate; a prefix below the model's minimum cacheable
	// size is silently not cached (no error).
	if n := len(out.System); n > 0 {
		out.System[n-1] = withCacheControl(out.System[n-1])
	}
	if n := len(out.Messages); n > 0 {
		if c := len(out.Messages[n-1].Content); c > 0 {
			out.Messages[n-1].Content[c-1] = withCacheControl(out.Messages[n-1].Content[c-1])
		}
	}

	return json.Marshal(out)
}

// withCacheControl adds an ephemeral cache_control marker to an Anthropic content block, turning it
// into a prompt-cache breakpoint. Returns the block unchanged if it isn't a JSON object.
func withCacheControl(block json.RawMessage) json.RawMessage {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(block, &m); err != nil {
		return block
	}
	m["cache_control"] = json.RawMessage(`{"type":"ephemeral"}`)
	b, err := json.Marshal(m)
	if err != nil {
		return block
	}
	return b
}

// clampTemperature maps OpenAI's [0,2] temperature domain into Anthropic's accepted [0,1] so a
// valid OpenAI request (e.g. temperature 1.7) isn't turned into an upstream 400.
func clampTemperature(t *float64) *float64 {
	if t == nil {
		return nil
	}
	v := *t
	if v > 1 {
		v = 1
	}
	if v < 0 {
		v = 0
	}
	return &v
}

// parseStop reads OpenAI `stop` (string | []string) into Anthropic stop_sequences.
func parseStop(raw json.RawMessage) []string {
	if len(raw) == 0 {
		return nil
	}
	var s string
	if json.Unmarshal(raw, &s) == nil {
		if s == "" {
			return nil
		}
		return []string{s}
	}
	var arr []string
	if json.Unmarshal(raw, &arr) == nil {
		return arr
	}
	return nil
}

// extractText pulls plain text out of an OpenAI content field (string | []part | null).
func extractText(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s
	}
	var parts []contentPart
	if json.Unmarshal(raw, &parts) == nil {
		var b strings.Builder
		for _, p := range parts {
			if p.Type == "text" || (p.Type == "" && p.Text != "") {
				b.WriteString(p.Text)
			}
		}
		return b.String()
	}
	return ""
}

// userContentBlocks converts an OpenAI user content field into Anthropic content blocks
// (text + image), preserving order.
func userContentBlocks(raw json.RawMessage) []json.RawMessage {
	if len(raw) == 0 {
		return nil
	}
	var s string
	if json.Unmarshal(raw, &s) == nil {
		if s == "" {
			return nil
		}
		return []json.RawMessage{textBlock(s)}
	}
	var parts []contentPart
	if json.Unmarshal(raw, &parts) != nil {
		return nil
	}
	var blocks []json.RawMessage
	for _, p := range parts {
		switch {
		case p.Type == "image_url" && p.ImageURL != nil:
			blocks = append(blocks, imageBlock(p.ImageURL.URL))
		case p.Type == "text" || p.Text != "":
			blocks = append(blocks, textBlock(p.Text))
		}
	}
	return blocks
}

func textBlock(text string) json.RawMessage {
	b, _ := json.Marshal(map[string]any{"type": "text", "text": text})
	return b
}

func toolUseBlock(tc toolCall) json.RawMessage {
	var input json.RawMessage = json.RawMessage(`{}`)
	if strings.TrimSpace(tc.Function.Arguments) != "" && json.Valid([]byte(tc.Function.Arguments)) {
		input = json.RawMessage(tc.Function.Arguments)
	}
	b, _ := json.Marshal(map[string]any{
		"type":  "tool_use",
		"id":    tc.ID,
		"name":  tc.Function.Name,
		"input": input,
	})
	return b
}

func toolResultBlock(toolUseID, content string) json.RawMessage {
	b, _ := json.Marshal(map[string]any{
		"type":         "tool_result",
		"tool_use_id":  toolUseID,
		"content":      content,
	})
	return b
}

// imageBlock maps an OpenAI image_url (data: URL or http URL) to an Anthropic image block.
func imageBlock(url string) json.RawMessage {
	if strings.HasPrefix(url, "data:") {
		// data:<media_type>;base64,<data>
		if comma := strings.IndexByte(url, ','); comma > 0 {
			meta := url[len("data:"):comma]
			data := url[comma+1:]
			mediaType := meta
			if semi := strings.IndexByte(meta, ';'); semi >= 0 {
				mediaType = meta[:semi]
			}
			b, _ := json.Marshal(map[string]any{
				"type": "image",
				"source": map[string]any{
					"type":       "base64",
					"media_type": mediaType,
					"data":       data,
				},
			})
			return b
		}
	}
	b, _ := json.Marshal(map[string]any{
		"type":   "image",
		"source": map[string]any{"type": "url", "url": url},
	})
	return b
}

// translateToolChoice maps OpenAI tool_choice to Anthropic tool_choice.
func translateToolChoice(raw json.RawMessage) json.RawMessage {
	if len(raw) == 0 {
		return nil
	}
	var s string
	if json.Unmarshal(raw, &s) == nil {
		switch s {
		case "required":
			return json.RawMessage(`{"type":"any"}`)
		case "none":
			// OpenAI "none" = must NOT call a tool. Anthropic has an explicit equivalent.
			return json.RawMessage(`{"type":"none"}`)
		case "auto":
			return json.RawMessage(`{"type":"auto"}`)
		default:
			return nil
		}
	}
	var obj struct {
		Type     string `json:"type"`
		Function struct {
			Name string `json:"name"`
		} `json:"function"`
	}
	if json.Unmarshal(raw, &obj) == nil && obj.Function.Name != "" {
		b, _ := json.Marshal(map[string]any{"type": "tool", "name": obj.Function.Name})
		return b
	}
	return nil
}

func isForcedToolChoice(raw json.RawMessage) bool {
	var tc struct {
		Type string `json:"type"`
	}
	return len(raw) > 0 && json.Unmarshal(raw, &tc) == nil && (tc.Type == "any" || tc.Type == "tool")
}

// finishReasonFor maps an Anthropic stop_reason to an OpenAI finish_reason.
func finishReasonFor(stopReason string) string {
	switch stopReason {
	case "max_tokens":
		return "length"
	case "tool_use":
		return "tool_calls"
	case "end_turn", "stop_sequence":
		return "stop"
	case "":
		return ""
	default:
		return "stop"
	}
}
