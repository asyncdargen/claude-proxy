// Package anthropicgw is the Translator for the Anthropic-native routing gateway. It exposes the
// real Anthropic Messages API contract to clients (any Anthropic SDK / raw HTTP), but under the
// hood serves each request from a Claude Code subscription account: it injects the Claude Code
// system prompt so OAuth accounts accept the traffic, then relays Anthropic's response verbatim.
package anthropicgw

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"

	"claudeproxy/gateway/internal/anthropic"
	"claudeproxy/gateway/internal/ccident"
	"claudeproxy/gateway/internal/models"
	"claudeproxy/gateway/internal/routing"
)

// Translator implements routing.Translator for the Anthropic protocol.
type Translator struct{}

func New() *Translator { return &Translator{} }

func (t *Translator) Name() string              { return "anthropic" }
func (t *Translator) StreamContentType() string { return "text/event-stream" }

// HandleLocal serves GET /v1/models with an Anthropic-shaped model list (no upstream call).
func (t *Translator) HandleLocal(w http.ResponseWriter, method, path string) bool {
	if method == http.MethodGet && (path == "/v1/models" || strings.HasPrefix(path, "/v1/models/")) {
		writeModels(w, path)
		return true
	}
	return false
}

// Prepare injects the Claude Code system prompt and forwards the (still Anthropic) body verbatim.
func (t *Translator) Prepare(w http.ResponseWriter, method, path, requestURI string, body []byte) (routing.Prepared, bool) {
	var meta struct {
		Model  string `json:"model"`
		Stream bool   `json:"stream"`
	}
	_ = json.Unmarshal(body, &meta)
	outBody := body
	if len(body) > 0 {
		outBody = ccident.InjectSystemPrompt(body)
	}
	return routing.Prepared{
		Method:         method,
		UpstreamPath:   requestURI,
		Body:           outBody,
		RequestedModel: meta.Model,
		Stream:         meta.Stream,
	}, true
}

// NewStreamWriter passes Anthropic SSE bytes through verbatim while scanning usage.
func (t *Translator) NewStreamWriter(w io.Writer, p routing.Prepared) routing.StreamWriter {
	return &passthrough{w: w, usage: anthropic.Usage{Status: 200}}
}

// RelayJSON writes the upstream Anthropic JSON verbatim and scans usage from it.
func (t *Translator) RelayJSON(w http.ResponseWriter, status int, upstream []byte, p routing.Prepared) routing.Usage {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(upstream)

	u := routing.Usage{Status: status}
	var obj struct {
		Model string `json:"model"`
		Usage struct {
			Input       int64 `json:"input_tokens"`
			Output      int64 `json:"output_tokens"`
			CacheRead   int64 `json:"cache_read_input_tokens"`
			CacheCreate int64 `json:"cache_creation_input_tokens"`
		} `json:"usage"`
	}
	if json.Unmarshal(upstream, &obj) == nil {
		u.Model = obj.Model
		u.Input, u.Output = obj.Usage.Input, obj.Usage.Output
		u.CacheRead, u.CacheWrite = obj.Usage.CacheRead, obj.Usage.CacheCreate
	}
	return u
}

// WriteError emits an Anthropic-shaped error JSON.
func (t *Translator) WriteError(w http.ResponseWriter, status int, kind, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"type":  "error",
		"error": map[string]string{"type": kind, "message": message},
	})
}

// passthrough is the streaming writer: it forwards Anthropic SSE bytes unchanged and scans usage.
type passthrough struct {
	w      io.Writer
	parser anthropic.SSEParser
	usage  anthropic.Usage
}

func (s *passthrough) Feed(p []byte) {
	_, _ = s.w.Write(p)
	for _, ev := range s.parser.Feed(p) {
		s.usage.Consume(ev)
	}
}

func (s *passthrough) Finish() {}

func (s *passthrough) Usage() routing.Usage {
	return routing.Usage{
		Input:      s.usage.Input,
		Output:     s.usage.Output,
		CacheRead:  s.usage.CacheRead,
		CacheWrite: s.usage.CacheWrite,
		Model:      s.usage.Model,
		Status:     s.usage.Status,
	}
}

func writeModels(w http.ResponseWriter, path string) {
	// /v1/models/{id} → single model; /v1/models → list.
	if id := strings.TrimPrefix(path, "/v1/models/"); id != path && id != "" {
		if m, ok := models.Find(id); ok {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(anthModel(m))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		_ = json.NewEncoder(w).Encode(map[string]any{"type": "error", "error": map[string]string{"type": "not_found_error", "message": "model not found"}})
		return
	}
	list := models.List()
	data := make([]map[string]any, 0, len(list))
	for _, m := range list {
		data = append(data, anthModel(m))
	}
	w.Header().Set("Content-Type", "application/json")
	resp := map[string]any{"data": data, "has_more": false}
	if len(list) > 0 {
		resp["first_id"] = list[0].ID
		resp["last_id"] = list[len(list)-1].ID
	}
	_ = json.NewEncoder(w).Encode(resp)
}

func anthModel(m models.Model) map[string]any {
	return map[string]any{
		"type":         "model",
		"id":           m.ID,
		"display_name": m.DisplayName,
		"created_at":   m.CreatedAtOrDefault(),
	}
}
