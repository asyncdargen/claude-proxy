package openaigw

import (
	"encoding/json"
	"testing"

	"claudeproxy/gateway/internal/ccident"
)

func mustTranslate(t *testing.T, body string, model string) anthRequest {
	t.Helper()
	var req chatRequest
	if err := json.Unmarshal([]byte(body), &req); err != nil {
		t.Fatalf("parse request: %v", err)
	}
	out, err := translateRequest(&req, model)
	if err != nil {
		t.Fatalf("translate: %v", err)
	}
	var ar anthRequest
	if err := json.Unmarshal(out, &ar); err != nil {
		t.Fatalf("parse anthropic body: %v\n%s", err, out)
	}
	return ar
}

func blockField(t *testing.T, raw json.RawMessage, field string) string {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("block: %v", err)
	}
	s, _ := m[field].(string)
	return s
}

func TestTranslateBasicWithSystem(t *testing.T) {
	ar := mustTranslate(t, `{
		"model":"gpt-4o",
		"messages":[
			{"role":"system","content":"Be nice."},
			{"role":"user","content":"Hello"}
		],
		"max_tokens":100,
		"temperature":0.5
	}`, "claude-sonnet-4-5")

	if ar.Model != "claude-sonnet-4-5" || ar.MaxTokens != 100 {
		t.Fatalf("model/max_tokens wrong: %+v", ar)
	}
	if ar.Temperature == nil || *ar.Temperature != 0.5 {
		t.Fatalf("temperature not carried")
	}
	if len(ar.System) != 2 || blockField(t, ar.System[0], "text") != ccident.SystemPrompt {
		t.Fatalf("system[0] must be Claude Code prompt: %v", ar.System)
	}
	if blockField(t, ar.System[1], "text") != "Be nice." {
		t.Fatalf("client system not preserved")
	}
	if len(ar.Messages) != 1 || ar.Messages[0].Role != "user" {
		t.Fatalf("messages wrong: %+v", ar.Messages)
	}
	if blockField(t, ar.Messages[0].Content[0], "text") != "Hello" {
		t.Fatalf("user text wrong")
	}
}

func TestTranslateDefaultMaxTokens(t *testing.T) {
	ar := mustTranslate(t, `{"model":"x","messages":[{"role":"user","content":"hi"}]}`, "claude-x")
	if ar.MaxTokens != defaultMaxTokens {
		t.Fatalf("default max_tokens = %d, want %d", ar.MaxTokens, defaultMaxTokens)
	}
}

func TestTranslateToolsAndChoice(t *testing.T) {
	ar := mustTranslate(t, `{
		"model":"x",
		"messages":[{"role":"user","content":"weather?"}],
		"tools":[{"type":"function","function":{"name":"get_weather","description":"w","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}],
		"tool_choice":"required"
	}`, "claude-x")

	if len(ar.Tools) != 1 || ar.Tools[0].Name != "get_weather" {
		t.Fatalf("tools wrong: %+v", ar.Tools)
	}
	var schema map[string]any
	_ = json.Unmarshal(ar.Tools[0].InputSchema, &schema)
	if schema["type"] != "object" {
		t.Fatalf("input_schema not carried: %s", ar.Tools[0].InputSchema)
	}
	var tc map[string]any
	_ = json.Unmarshal(ar.ToolChoice, &tc)
	if tc["type"] != "any" {
		t.Fatalf("tool_choice required -> any, got %v", tc)
	}
}

func TestTranslateNamedToolChoice(t *testing.T) {
	ar := mustTranslate(t, `{
		"model":"x","messages":[{"role":"user","content":"x"}],
		"tools":[{"type":"function","function":{"name":"f","parameters":{}}}],
		"tool_choice":{"type":"function","function":{"name":"f"}}
	}`, "claude-x")
	var tc map[string]any
	_ = json.Unmarshal(ar.ToolChoice, &tc)
	if tc["type"] != "tool" || tc["name"] != "f" {
		t.Fatalf("named tool_choice wrong: %v", tc)
	}
}

func TestTranslateAssistantToolCallsAndToolResult(t *testing.T) {
	ar := mustTranslate(t, `{
		"model":"x",
		"messages":[
			{"role":"user","content":"weather in Paris?"},
			{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}]},
			{"role":"tool","tool_call_id":"call_1","content":"20C"}
		]
	}`, "claude-x")

	if len(ar.Messages) != 3 {
		t.Fatalf("want 3 messages (user, assistant, user-toolresult), got %d: %+v", len(ar.Messages), ar.Messages)
	}
	// assistant turn holds a tool_use block
	var tu map[string]any
	_ = json.Unmarshal(ar.Messages[1].Content[0], &tu)
	if tu["type"] != "tool_use" || tu["name"] != "get_weather" || tu["id"] != "call_1" {
		t.Fatalf("tool_use block wrong: %v", tu)
	}
	if inp, ok := tu["input"].(map[string]any); !ok || inp["city"] != "Paris" {
		t.Fatalf("tool_use input not parsed: %v", tu["input"])
	}
	// tool result mapped to a user turn with tool_result block
	if ar.Messages[2].Role != "user" {
		t.Fatalf("tool result must be a user turn, got %s", ar.Messages[2].Role)
	}
	var tr map[string]any
	_ = json.Unmarshal(ar.Messages[2].Content[0], &tr)
	if tr["type"] != "tool_result" || tr["tool_use_id"] != "call_1" || tr["content"] != "20C" {
		t.Fatalf("tool_result wrong: %v", tr)
	}
}

func TestTranslateMergesConsecutiveSameRole(t *testing.T) {
	ar := mustTranslate(t, `{
		"model":"x",
		"messages":[
			{"role":"user","content":"one"},
			{"role":"user","content":"two"}
		]
	}`, "claude-x")
	if len(ar.Messages) != 1 || len(ar.Messages[0].Content) != 2 {
		t.Fatalf("consecutive user messages should merge into one turn with 2 blocks: %+v", ar.Messages)
	}
}

func TestTranslateImagePart(t *testing.T) {
	ar := mustTranslate(t, `{
		"model":"x",
		"messages":[{"role":"user","content":[
			{"type":"text","text":"look:"},
			{"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}}
		]}]
	}`, "claude-x")
	if len(ar.Messages[0].Content) != 2 {
		t.Fatalf("want text+image blocks, got %d", len(ar.Messages[0].Content))
	}
	var img map[string]any
	_ = json.Unmarshal(ar.Messages[0].Content[1], &img)
	src, _ := img["source"].(map[string]any)
	if img["type"] != "image" || src["type"] != "base64" || src["media_type"] != "image/png" || src["data"] != "QUJD" {
		t.Fatalf("image block wrong: %v", img)
	}
}

func TestTranslateResponseTextAndUsage(t *testing.T) {
	st := &state{echoModel: "gpt-4o", id: "chatcmpl-abc", created: 1}
	body, u := translateResponse([]byte(`{
		"id":"msg_1","model":"claude-sonnet-4-5",
		"content":[{"type":"text","text":"Hi there"}],
		"stop_reason":"end_turn",
		"usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":3}
	}`), st)

	var resp map[string]any
	if err := json.Unmarshal(body, &resp); err != nil {
		t.Fatalf("bad openai json: %v", err)
	}
	if resp["object"] != "chat.completion" || resp["model"] != "gpt-4o" {
		t.Fatalf("envelope wrong: %v", resp)
	}
	choices := resp["choices"].([]any)
	choice := choices[0].(map[string]any)
	if choice["finish_reason"] != "stop" {
		t.Fatalf("finish_reason = %v", choice["finish_reason"])
	}
	msg := choice["message"].(map[string]any)
	if msg["content"] != "Hi there" {
		t.Fatalf("content = %v", msg["content"])
	}
	usage := resp["usage"].(map[string]any)
	if usage["prompt_tokens"].(float64) != 13 || usage["completion_tokens"].(float64) != 5 {
		t.Fatalf("usage wrong: %v", usage)
	}
	// usage struct for the service report keeps the four kinds separate + real Claude model.
	if u.Input != 10 || u.Output != 5 || u.CacheRead != 3 || u.Model != "claude-sonnet-4-5" {
		t.Fatalf("report usage wrong: %+v", u)
	}
}

func TestTranslateResponseToolCalls(t *testing.T) {
	st := &state{echoModel: "gpt-4o", id: "id", created: 1}
	body, _ := translateResponse([]byte(`{
		"id":"m","model":"claude-x",
		"content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Paris"}}],
		"stop_reason":"tool_use",
		"usage":{"input_tokens":1,"output_tokens":1}
	}`), st)
	var resp map[string]any
	_ = json.Unmarshal(body, &resp)
	choice := resp["choices"].([]any)[0].(map[string]any)
	if choice["finish_reason"] != "tool_calls" {
		t.Fatalf("finish_reason = %v", choice["finish_reason"])
	}
	msg := choice["message"].(map[string]any)
	if msg["content"] != nil {
		t.Fatalf("content should be null when only tool calls, got %v", msg["content"])
	}
	tcs := msg["tool_calls"].([]any)
	tc := tcs[0].(map[string]any)
	fn := tc["function"].(map[string]any)
	if tc["id"] != "toolu_1" || fn["name"] != "get_weather" {
		t.Fatalf("tool_call wrong: %v", tc)
	}
	if fn["arguments"] != `{"city":"Paris"}` {
		t.Fatalf("arguments must be a JSON string, got %v", fn["arguments"])
	}
}

func TestTranslateTemperatureClamp(t *testing.T) {
	// OpenAI allows up to 2.0; Anthropic rejects >1, so it must be clamped.
	hi := mustTranslate(t, `{"model":"x","messages":[{"role":"user","content":"hi"}],"temperature":1.7}`, "claude-x")
	if hi.Temperature == nil || *hi.Temperature != 1.0 {
		t.Fatalf("temperature 1.7 should clamp to 1.0, got %v", hi.Temperature)
	}
	// In-range values pass through unchanged.
	lo := mustTranslate(t, `{"model":"x","messages":[{"role":"user","content":"hi"}],"temperature":0.5}`, "claude-x")
	if lo.Temperature == nil || *lo.Temperature != 0.5 {
		t.Fatalf("temperature 0.5 should pass through, got %v", lo.Temperature)
	}
}

func TestTranslateToolChoiceNone(t *testing.T) {
	ar := mustTranslate(t, `{"model":"x","messages":[{"role":"user","content":"x"}],"tools":[{"type":"function","function":{"name":"f","parameters":{}}}],"tool_choice":"none"}`, "claude-x")
	var tc map[string]any
	_ = json.Unmarshal(ar.ToolChoice, &tc)
	if tc["type"] != "none" {
		t.Fatalf("tool_choice none -> {type:none}, got %v", tc)
	}
}

func TestModelMapping(t *testing.T) {
	tr := &Translator{defaultModel: "claude-sonnet-5", modelMap: map[string]string{"my-alias": "claude-opus-4-8"}}
	cases := map[string]string{
		"gpt-4o":                    "claude-sonnet-5",
		"gpt-4o-mini":               "claude-haiku-4-5-20251001",
		"o1-opus-ish-opus":          "claude-opus-5-5",           // newest opus in the catalogue
		"claude-haiku-4-5-20251001": "claude-haiku-4-5-20251001", // claude-* passthrough
		"my-alias":                  "claude-opus-4-8",
		"":                          "claude-sonnet-5",
	}
	for in, want := range cases {
		if got := tr.mapModel(in); got != want {
			t.Errorf("mapModel(%q) = %q, want %q", in, got, want)
		}
	}
}

func hasEphemeralCache(t *testing.T, raw json.RawMessage) bool {
	t.Helper()
	var m map[string]json.RawMessage
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("block: %v", err)
	}
	cc, ok := m["cache_control"]
	if !ok {
		return false
	}
	var got map[string]any
	_ = json.Unmarshal(cc, &got)
	return got["type"] == "ephemeral"
}

func TestTranslateCacheControl(t *testing.T) {
	// Two consecutive user turns merge into one message with two blocks (see the merge test).
	ar := mustTranslate(t, `{
		"model":"gpt-4o",
		"messages":[
			{"role":"system","content":"Be nice."},
			{"role":"user","content":"one"},
			{"role":"user","content":"two"}
		]
	}`, "claude-opus-4-8")

	// The stable prefix breakpoint sits on the LAST system block; the Claude Code identity block
	// before it must stay clean (one breakpoint caches the whole tools+system prefix).
	if len(ar.System) != 2 {
		t.Fatalf("want 2 system blocks, got %d", len(ar.System))
	}
	if hasEphemeralCache(t, ar.System[0]) {
		t.Fatalf("Claude Code identity block must not carry cache_control")
	}
	if !hasEphemeralCache(t, ar.System[1]) {
		t.Fatalf("last system block must be a cache breakpoint")
	}

	// The conversation breakpoint sits on the tail block only.
	last := ar.Messages[len(ar.Messages)-1]
	if n := len(last.Content); n != 2 {
		t.Fatalf("want merged 2-block tail message, got %d", n)
	}
	if hasEphemeralCache(t, last.Content[0]) {
		t.Fatalf("only the tail block should carry cache_control")
	}
	if !hasEphemeralCache(t, last.Content[1]) {
		t.Fatalf("tail message block must be a cache breakpoint")
	}
}

func TestTranslateDropsWhatOpus55Rejects(t *testing.T) {
	// Opus 5.5 answers temperature/top_p and a forced tool_choice with a 400.
	ar := mustTranslate(t, `{"model":"x","messages":[{"role":"user","content":"x"}],"temperature":0.2,"top_p":0.9,
		"tools":[{"type":"function","function":{"name":"f","parameters":{}}}],"tool_choice":"required"}`, "claude-opus-5-5")
	if ar.Temperature != nil || ar.TopP != nil {
		t.Fatalf("sampling params must be dropped, got %v / %v", ar.Temperature, ar.TopP)
	}
	var tc map[string]any
	_ = json.Unmarshal(ar.ToolChoice, &tc)
	if tc["type"] != "auto" {
		t.Fatalf("forced tool_choice must fall back to auto, got %v", tc)
	}
	// Opus 5 still takes a forced tool_choice.
	o5 := mustTranslate(t, `{"model":"x","messages":[{"role":"user","content":"x"}],
		"tools":[{"type":"function","function":{"name":"f","parameters":{}}}],"tool_choice":{"type":"function","function":{"name":"f"}}}`, "claude-opus-5")
	_ = json.Unmarshal(o5.ToolChoice, &tc)
	if tc["type"] != "tool" {
		t.Fatalf("Opus 5 keeps the named tool_choice, got %v", tc)
	}
}
