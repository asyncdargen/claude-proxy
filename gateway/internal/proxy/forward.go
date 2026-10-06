package proxy

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"

	"claudeproxy/gateway/internal/anthropic"
	"claudeproxy/gateway/internal/control"
)

// forwardResult is the outcome of a single upstream attempt.
type forwardResult struct {
	retry  bool
	report control.UsageReport
	// headSent means the 200 response head is already on the wire. A retry after that keeps
	// streaming into the same open response instead of writing a second head.
	headSent bool
}

// Hop-by-hop / auth / client-origin headers we never forward upstream verbatim. `x-client-id` is a
// proxy tell (real Claude Code omits it); the session-id header is re-emitted rotated in B5.
var stripRequestHeaders = map[string]bool{
	"host": true, "content-length": true, "transfer-encoding": true, "connection": true,
	"authorization": true, "x-api-key": true, "accept-encoding": true,
	"proxy-authorization": true, "proxy-authenticate": true, "cookie": true, "cookie2": true,
	"forwarded": true, "x-real-ip": true, "x-client-ip": true, "x-cluster-client-ip": true,
	"x-originating-ip": true, "x-original-forwarded-for": true, "cf-connecting-ip": true,
	"cf-connecting-ipv6": true, "true-client-ip": true, "fastly-client-ip": true,
	"x-client-id": true, "x-claude-code-session-id": true,
}

// Statuses that make a non-last attempt retry the next account instead of passing through.
// 403 is in here because upstream returns it for account-scoped problems — a suspended or
// past-due subscription — which the *next* account is unaffected by; passing it straight through
// would fail a request the pool could still serve.
var retryableStatuses = map[int]bool{429: true, 401: true, 403: true, 500: true, 502: true, 503: true, 529: true}

// forward performs one upstream attempt against a candidate. On a retryable status with
// canRetry it drains the body and returns {retry:true}; otherwise it relays the response
// (SSE or buffered JSON) to the client and returns {retry:false}.
func (h *Handler) forward(
	ctx context.Context, w http.ResponseWriter, r *http.Request,
	cand control.Candidate, cands []control.Candidate, idx int, userID, tokenID *int,
	body []byte, canRetry, headSent bool,
) forwardResult {
	outBody, sessionID := rewriteBody(body, cand.DeviceID, cand.AccountUUID, r.Header.Get("X-Claude-Code-Session-Id"))

	url := h.cfg.UpstreamBaseURL + r.URL.RequestURI()
	req, err := http.NewRequestWithContext(ctx, r.Method, url, strings.NewReader(string(outBody)))
	if err != nil {
		return forwardResult{retry: canRetry, report: control.UsageReport{AccountID: cand.AccountID, UserID: userID, TokenID: tokenID, Status: 0}}
	}

	// Copy client headers except the strip-set and telemetry headers.
	for name, vals := range r.Header {
		ln := strings.ToLower(name)
		if stripRequestHeaders[ln] || strings.HasPrefix(ln, "x-forwarded-") || isTelemetryHeader(ln) {
			continue
		}
		for _, v := range vals {
			req.Header.Add(name, v)
		}
	}
	// Re-emit the session-id header with this account's rotated value.
	if sessionID != "" {
		req.Header.Set("X-Claude-Code-Session-Id", sessionID)
	}
	// Per-account upstream credentials (already decrypted by the service). Swap credential
	// headers (Authorization / x-api-key) outright, but MERGE anthropic-beta into whatever the
	// client sent — overwriting it would drop client betas (e.g. context-management-*), which
	// then makes the matching body field a "400 extra inputs" error. Mirrors UpstreamAuth.apply.
	for k, v := range cand.AuthHeaders {
		if strings.EqualFold(k, "anthropic-beta") {
			req.Header.Set("anthropic-beta", mergeBeta(req.Header.Values("anthropic-beta"), v))
		} else {
			req.Header.Set(k, v)
		}
	}
	// Anthropic requires this header; inject a default if the client omitted it.
	if r.Header.Get("anthropic-version") == "" {
		req.Header.Set("anthropic-version", "2023-06-01")
	}
	if len(outBody) == 0 {
		req.Body = http.NoBody
		req.ContentLength = 0
	} else {
		req.ContentLength = int64(len(outBody))
	}

	resp, headSent, err := h.doWithEarlyHead(w, req, streamRequested(outBody), headSent)
	if err != nil {
		if canRetry {
			return forwardResult{retry: true, headSent: headSent, report: control.UsageReport{AccountID: cand.AccountID, UserID: userID, TokenID: tokenID, Status: 0}}
		}
		failAfterHead(w, headSent, cands, idx, http.StatusBadGateway, "api_error", "upstream request failed")
		return forwardResult{retry: false, headSent: headSent, report: control.UsageReport{AccountID: cand.AccountID, UserID: userID, TokenID: tokenID, Status: http.StatusBadGateway}}
	}
	defer resp.Body.Close()

	rlHeaders := extractRateLimitHeaders(resp.Header)
	report := control.UsageReport{AccountID: cand.AccountID, UserID: userID, TokenID: tokenID, Status: resp.StatusCode, RatelimitHeaders: rlHeaders}

	// Retryable status on a non-last attempt: drain and let the caller try the next account.
	if retryableStatuses[resp.StatusCode] && canRetry {
		_, _ = io.Copy(io.Discard, resp.Body)
		return forwardResult{retry: true, headSent: headSent, report: report}
	}

	// Pass the real upstream response through to the client.
	contentType := resp.Header.Get("Content-Type")
	if !headSent {
		copyResponseHeaders(w, resp.Header)
	}

	if strings.Contains(strings.ToLower(contentType), "text/event-stream") {
		res := relaySSE(w, resp.Body, resp.StatusCode, contentType, headSent, canRetry, func() (string, int) {
			return midStreamError(cands, idx)
		}, h.cfg.UpstreamStallTimeout)
		report.Status = res.status
		applyUsage(&report, res.usage, modelFromRequest(outBody))
		report.McpCalls = res.mcp
		// The head is out from here on, whether we're retrying or done.
		return forwardResult{retry: res.retry, headSent: true, report: report}
	}

	// Buffer the (single) JSON message so we can extract token usage.
	buf, _ := io.ReadAll(resp.Body)
	fillUsageFromJSON(&report, buf, modelFromRequest(outBody))
	if headSent {
		// An earlier attempt already opened a streamed 200 (and hit a retryable error before any
		// content); this one answered with plain JSON, so there is no status left to send. Close
		// the open stream with a normalized error frame instead of a bare truncation.
		failAfterHead(w, true, cands, idx, resp.StatusCode, "api_error", "upstream returned a non-streamed response")
		return forwardResult{retry: false, headSent: true, report: report}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(buf)
	return forwardResult{retry: false, headSent: false, report: report}
}

// doWithEarlyHead runs the upstream request, opening the client's stream *before* the answer when
// the response head takes too long, and keeping it warm with keep-alive comments. It returns the
// upstream response and whether the head is on the wire now.
//
// Without this the client sees zero bytes for the whole wait — Anthropic's own think time plus
// every retried candidate ahead of this one — and whatever sits in front of the gateway times the
// request out on that silence (Cloudflare: 524 at 120s). The comments are the same ones relaySSE
// sends during silence: SSE consumers discard them, so an early head costs the client nothing and
// still leaves the stream a blank slate another account can take over.
func (h *Handler) doWithEarlyHead(
	w http.ResponseWriter, req *http.Request, streaming, headSent bool,
) (*http.Response, bool, error) {
	// A non-streaming client is waiting on a JSON body — opening an event-stream for it would
	// hand it a response it cannot parse, so those requests keep waiting in silence.
	if headSent || !streaming || h.cfg.EarlyHeadTimeout <= 0 {
		resp, err := h.upstream.Do(req)
		return resp, headSent, err
	}

	type upstreamResult struct {
		resp *http.Response
		err  error
	}
	// Buffered so the request goroutine can always finish, even if we stopped reading.
	done := make(chan upstreamResult, 1)
	go func() {
		resp, err := h.upstream.Do(req)
		done <- upstreamResult{resp, err}
	}()

	fl, _ := w.(http.Flusher)
	wait := time.NewTimer(h.cfg.EarlyHeadTimeout)
	defer wait.Stop()
	for {
		select {
		case res := <-done:
			return res.resp, headSent, res.err
		case <-wait.C:
			if !headSent {
				w.Header().Set("Content-Type", "text/event-stream")
				w.WriteHeader(http.StatusOK)
				headSent = true
			}
			_, _ = io.WriteString(w, ": keep-alive\n\n")
			if fl != nil {
				fl.Flush()
			}
			wait.Reset(keepAliveInterval)
		}
	}
}

// streamRequested reports whether the client asked for an SSE response.
func streamRequested(body []byte) bool {
	var obj struct {
		Stream bool `json:"stream"`
	}
	return json.Unmarshal(body, &obj) == nil && obj.Stream
}

// failAfterHead reports a terminal failure to the client, picking the only form still available:
// a normal JSON error when nothing has been sent yet, or an SSE error frame on an already-open
// stream (where the status line is long gone).
func failAfterHead(
	w http.ResponseWriter, headSent bool, cands []control.Candidate, idx int,
	status int, errType, message string,
) {
	if !headSent {
		writeProxyError(w, status, errType, message)
		return
	}
	frame, _ := midStreamError(cands, idx)
	_, _ = io.WriteString(w, frame)
	if fl, ok := w.(http.Flusher); ok {
		fl.Flush()
	}
}

// mergeBeta merges the account's anthropic-beta token(s) into the client's existing
// anthropic-beta values, preserving the client's betas (e.g. context-management-*) and
// de-duplicating. Order: client betas first, then any account betas not already present.
func mergeBeta(clientVals []string, accountBeta string) string {
	seen := map[string]bool{}
	var out []string
	add := func(csv string) {
		for _, tok := range strings.Split(csv, ",") {
			t := strings.TrimSpace(tok)
			if t == "" || seen[t] {
				continue
			}
			seen[t] = true
			out = append(out, t)
		}
	}
	for _, v := range clientVals {
		add(v)
	}
	add(accountBeta)
	return strings.Join(out, ",")
}

// copyResponseHeaders copies safe upstream headers to the client, dropping framing headers the
// Go server manages itself.
func copyResponseHeaders(w http.ResponseWriter, src http.Header) {
	for name, vals := range src {
		ln := strings.ToLower(name)
		if ln == "content-length" || ln == "transfer-encoding" || ln == "connection" || ln == "content-encoding" {
			continue
		}
		for _, v := range vals {
			w.Header().Add(name, v)
		}
	}
}

// extractRateLimitHeaders keeps only the headers the service's RateLimitHeaders parser reads.
func extractRateLimitHeaders(h http.Header) map[string]string {
	out := make(map[string]string)
	for name, vals := range h {
		ln := strings.ToLower(name)
		if strings.HasPrefix(ln, "anthropic-ratelimit") || ln == "retry-after" {
			if len(vals) > 0 {
				out[ln] = vals[len(vals)-1]
			}
		}
	}
	return out
}

// fillUsageFromJSON pulls model + token counts + MCP tool-call counts out of a buffered JSON
// response. [requestModel] is the fallback when the body carries no model of its own.
func fillUsageFromJSON(report *control.UsageReport, body []byte, requestModel *string) {
	applyUsage(report, anthropic.ParseMessageJSON(body), requestModel)
	var obj struct {
		Content []contentBlockHead `json:"content"`
	}
	if json.Unmarshal(body, &obj) != nil {
		return
	}
	var mcp mcpScan
	for _, block := range obj.Content {
		mcp.count(block)
	}
	report.McpCalls = mcp.calls
}

// applyUsage copies a scanned usage into the report. The model is taken from the *response*
// whenever it names one: with server-side refusal fallback the model that answered is not the
// one the request asked for, and pricing must follow whoever actually did the work. The request
// model is only the fallback (error responses and count_tokens carry no model).
func applyUsage(report *control.UsageReport, u anthropic.Usage, requestModel *string) {
	report.Input, report.Output = u.Input, u.Output
	report.CacheRead, report.CacheWrite = u.CacheRead, u.CacheWrite
	report.CacheWrite1h = u.CacheWrite1h
	report.WebSearchRequests, report.WebFetchRequests = u.WebSearch, u.WebFetch
	report.Fast = u.Fast
	if u.Model != "" {
		m := u.Model
		report.Model = &m
		return
	}
	report.Model = requestModel
}

// modelFromRequest reads the "model" field from the request body.
func modelFromRequest(body []byte) *string {
	var obj struct {
		Model string `json:"model"`
	}
	if err := json.Unmarshal(body, &obj); err != nil || obj.Model == "" {
		return nil
	}
	return &obj.Model
}

// writeProxyError emits an Anthropic-shaped error JSON with the given HTTP status.
func writeProxyError(w http.ResponseWriter, status int, errType, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	body, _ := json.Marshal(map[string]any{"error": map[string]string{"type": errType, "message": message}})
	_, _ = w.Write(body)
}
