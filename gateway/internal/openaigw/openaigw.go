// Package openaigw is the Translator for the OpenAI-compatible routing gateway. It accepts OpenAI
// Chat Completions requests, translates them into Anthropic Messages calls served by a Claude Code
// subscription account, and translates the responses (buffered and streamed, including tool calls)
// back into the OpenAI wire format. Clients point the OpenAI SDK's base_url at this gateway.
package openaigw

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"claudeproxy/gateway/internal/models"
	"claudeproxy/gateway/internal/routing"
)

// Translator implements routing.Translator for the OpenAI protocol.
type Translator struct {
	defaultModel string
	modelMap     map[string]string
}

// New builds the OpenAI translator, reading the model mapping from the environment:
//   - DEFAULT_MODEL: Claude model for unmapped/unknown OpenAI model names (default claude-sonnet-4-5)
//   - OPENAI_MODEL_MAP: optional JSON object of exact {openaiName: claudeModel} overrides
func New() *Translator {
	def := os.Getenv("DEFAULT_MODEL")
	if def == "" {
		def = "claude-sonnet-5"
	}
	m := map[string]string{}
	if raw := os.Getenv("OPENAI_MODEL_MAP"); raw != "" {
		_ = json.Unmarshal([]byte(raw), &m)
	}
	return &Translator{defaultModel: def, modelMap: m}
}

func (t *Translator) Name() string              { return "openai" }
func (t *Translator) StreamContentType() string { return "text/event-stream" }

// state is the per-request data carried from Prepare to the response translators.
type state struct {
	echoModel    string // model string echoed back to the client (the requested one)
	id           string // chatcmpl id, stable across a streamed response
	created      int64
	includeUsage bool
}

// mapModel resolves an OpenAI model name to a Claude model id.
func (t *Translator) mapModel(requested string) string {
	if requested == "" {
		return t.defaultModel
	}
	if strings.HasPrefix(requested, "claude") {
		return requested // pass Claude ids straight through
	}
	if v, ok := t.modelMap[requested]; ok {
		return v
	}
	lower := strings.ToLower(requested)
	switch {
	case strings.Contains(lower, "opus"):
		return models.Newest("opus", "claude-opus-5-5")
	case strings.Contains(lower, "haiku"), strings.Contains(lower, "mini"),
		strings.Contains(lower, "small"), strings.Contains(lower, "nano"),
		strings.Contains(lower, "gpt-3.5"):
		return models.Newest("haiku", "claude-haiku-4-5-20251001")
	default:
		return t.defaultModel
	}
}

// HandleLocal serves GET /v1/models with an OpenAI-shaped model list (no upstream call).
func (t *Translator) HandleLocal(w http.ResponseWriter, method, path string) bool {
	if method == http.MethodGet && path == "/v1/models" {
		list := models.List()
		data := make([]map[string]any, 0, len(list))
		for _, m := range list {
			data = append(data, openaiModel(m))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"object": "list", "data": data})
		return true
	}
	if method == http.MethodGet && strings.HasPrefix(path, "/v1/models/") {
		id := strings.TrimPrefix(path, "/v1/models/")
		if m, ok := models.Find(id); ok {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(openaiModel(m))
			return true
		}
		t.WriteError(w, http.StatusNotFound, "invalid_request_error", "model '"+id+"' not found")
		return true
	}
	return false
}

// Prepare parses the OpenAI request and builds the Anthropic Messages upstream request.
func (t *Translator) Prepare(w http.ResponseWriter, method, path, requestURI string, body []byte) (routing.Prepared, bool) {
	// Exact match, not a prefix: the service decides "free path, no daily limit" from the URL, so
	// `/v1/chat/completions/count_tokens` must not reach a generation here.
	if strings.TrimSuffix(path, "/") != "/v1/chat/completions" {
		t.WriteError(w, http.StatusNotFound, "invalid_request_error", "Unsupported endpoint: "+path)
		return routing.Prepared{}, false
	}
	var req chatRequest
	if err := json.Unmarshal(body, &req); err != nil {
		t.WriteError(w, http.StatusBadRequest, "invalid_request_error", "Invalid JSON: "+err.Error())
		return routing.Prepared{}, false
	}
	if len(req.Messages) == 0 {
		t.WriteError(w, http.StatusBadRequest, "invalid_request_error", "messages is required")
		return routing.Prepared{}, false
	}
	claudeModel := t.mapModel(req.Model)
	anthBody, err := translateRequest(&req, claudeModel)
	if err != nil {
		t.WriteError(w, http.StatusBadRequest, "invalid_request_error", "Could not translate request: "+err.Error())
		return routing.Prepared{}, false
	}
	echo := req.Model
	if echo == "" {
		echo = claudeModel
	}
	st := &state{
		echoModel:    echo,
		id:           "chatcmpl-" + randHex(12),
		created:      time.Now().Unix(),
		includeUsage: req.StreamOptions != nil && req.StreamOptions.IncludeUsage,
	}
	return routing.Prepared{
		Method:         http.MethodPost,
		UpstreamPath:   "/v1/messages",
		Body:           anthBody,
		RequestedModel: claudeModel,
		Stream:         req.Stream,
		State:          st,
	}, true
}

// RelayJSON translates a buffered Anthropic response (or error) into an OpenAI chat.completion.
func (t *Translator) RelayJSON(w http.ResponseWriter, status int, upstream []byte, p routing.Prepared) routing.Usage {
	st, _ := p.State.(*state)
	w.Header().Set("Content-Type", "application/json")
	if status < 200 || status >= 300 {
		w.WriteHeader(status)
		_, _ = w.Write(openaiErrorFromAnthropic(upstream))
		return routing.Usage{Status: status}
	}
	body, u := translateResponse(upstream, st)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(body)
	u.Status = status
	return u
}

// WriteError emits an OpenAI-shaped error JSON.
func (t *Translator) WriteError(w http.ResponseWriter, status int, kind, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"error": map[string]any{"message": message, "type": kind, "code": nil},
	})
}

// translateResponse converts a successful Anthropic message into an OpenAI chat.completion body.
func translateResponse(upstream []byte, st *state) ([]byte, routing.Usage) {
	var ar struct {
		ID      string `json:"id"`
		Model   string `json:"model"`
		Content []struct {
			Type  string          `json:"type"`
			Text  string          `json:"text"`
			ID    string          `json:"id"`
			Name  string          `json:"name"`
			Input json.RawMessage `json:"input"`
		} `json:"content"`
		StopReason string `json:"stop_reason"`
		Usage      struct {
			Input       int64 `json:"input_tokens"`
			Output      int64 `json:"output_tokens"`
			CacheRead   int64 `json:"cache_read_input_tokens"`
			CacheCreate int64 `json:"cache_creation_input_tokens"`
		} `json:"usage"`
	}
	_ = json.Unmarshal(upstream, &ar)

	var textB strings.Builder
	var toolCalls []map[string]any
	for _, c := range ar.Content {
		switch c.Type {
		case "text":
			textB.WriteString(c.Text)
		case "tool_use":
			args := "{}"
			if len(c.Input) > 0 {
				args = string(c.Input)
			}
			toolCalls = append(toolCalls, map[string]any{
				"id":       c.ID,
				"type":     "function",
				"function": map[string]any{"name": c.Name, "arguments": args},
			})
		}
	}

	message := map[string]any{"role": "assistant"}
	if textB.Len() > 0 || len(toolCalls) == 0 {
		message["content"] = textB.String()
	} else {
		message["content"] = nil
	}
	if len(toolCalls) > 0 {
		message["tool_calls"] = toolCalls
	}

	finish := finishReasonFor(ar.StopReason)
	if finish == "" {
		finish = "stop"
	}
	id := st.id
	if id == "" {
		id = "chatcmpl-" + randHex(12)
	}
	resp := map[string]any{
		"id":      id,
		"object":  "chat.completion",
		"created": st.created,
		"model":   st.echoModel,
		"choices": []map[string]any{{
			"index":         0,
			"message":       message,
			"finish_reason": finish,
		}},
		"usage": map[string]any{
			"prompt_tokens":     ar.Usage.Input + ar.Usage.CacheRead + ar.Usage.CacheCreate,
			"completion_tokens": ar.Usage.Output,
			"total_tokens":      ar.Usage.Input + ar.Usage.CacheRead + ar.Usage.CacheCreate + ar.Usage.Output,
		},
	}
	out, _ := json.Marshal(resp)
	return out, routing.Usage{
		Input:      ar.Usage.Input,
		Output:     ar.Usage.Output,
		CacheRead:  ar.Usage.CacheRead,
		CacheWrite: ar.Usage.CacheCreate,
		Model:      ar.Model,
	}
}

// openaiErrorFromAnthropic reshapes an Anthropic error body into an OpenAI error body.
func openaiErrorFromAnthropic(upstream []byte) []byte {
	var ae struct {
		Error struct {
			Type    string `json:"type"`
			Message string `json:"message"`
		} `json:"error"`
	}
	msg := "upstream error"
	kind := "api_error"
	if json.Unmarshal(upstream, &ae) == nil && ae.Error.Message != "" {
		msg = ae.Error.Message
		if ae.Error.Type != "" {
			kind = ae.Error.Type
		}
	}
	out, _ := json.Marshal(map[string]any{
		"error": map[string]any{"message": msg, "type": kind, "code": nil},
	})
	return out
}

func openaiModel(m models.Model) map[string]any {
	return map[string]any{
		"id":       m.ID,
		"object":   "model",
		"created":  m.CreatedUnix(),
		"owned_by": "anthropic",
	}
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := io.ReadFull(rand.Reader, b); err != nil {
		return strings.Repeat("0", n*2)
	}
	return hex.EncodeToString(b)
}
