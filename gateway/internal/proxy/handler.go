// Package proxy is the gateway datapath: resolve → forward → SSE-relay → report.
package proxy

import (
	"context"
	"io"
	"net/http"
	"strings"

	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
	"claudeproxy/gateway/internal/egress"
)

// Handler is the datapath HTTP handler. It resolves each request against the service control
// API, then forwards to Anthropic across the ordered candidates with transparent retry.
type Handler struct {
	cfg      *config.Config
	ctrl     *control.Client
	upstream *http.Client
}

// NewHandler builds the datapath handler. The upstream client has NO overall timeout (SSE
// streams can run for minutes) but bounds dial + TLS handshake so a dead upstream fails fast.
func NewHandler(cfg *config.Config, ctrl *control.Client) *Handler {
	return &Handler{
		cfg:  cfg,
		ctrl: ctrl,
		upstream: &http.Client{
			Transport:     egress.AnthropicTransport(cfg.AnthropicProxyURL),
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	token := extractInboundToken(r)
	if token == "" {
		writeProxyError(w, http.StatusUnauthorized, "authentication_error", "Missing proxy token")
		return
	}

	pathAndQuery := r.URL.RequestURI()
	resp, status, err := h.ctrl.Resolve(r.Context(), token, r.Method, pathAndQuery)
	if err != nil {
		writeProxyError(w, http.StatusBadGateway, "api_error", "control API unreachable")
		return
	}
	switch status {
	case http.StatusUnauthorized:
		writeProxyError(w, http.StatusUnauthorized, "authentication_error", "Invalid proxy token")
		return
	case http.StatusForbidden:
		writeProxyError(w, http.StatusForbidden, "permission_error", "Token lacks proxy.use")
		return
	case http.StatusOK:
		// fall through
	default:
		writeProxyError(w, http.StatusBadGateway, "api_error", "control API error")
		return
	}
	// The resolve opened an "active now" entry for this request; close it however we leave.
	defer h.ctrl.EndSession(resp.SessionID)

	// The body is read only once the token is known good. Read first, an unauthenticated client
	// could make the gateway buffer up to the router's body cap per connection for free.
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeProxyError(w, http.StatusBadRequest, "invalid_request_error", "Failed to read request body")
		return
	}

	if len(resp.Candidates) == 0 {
		if resp.OverLimit {
			writeProxyError(w, http.StatusTooManyRequests, "rate_limit_error",
				"Daily spend limit reached; resets at 00:00 UTC. Add a personal account to keep working.")
			return
		}
		writeProxyError(w, http.StatusServiceUnavailable, "rate_limit_error", "No account is available for this token")
		return
	}

	if resp.DefaultModel != "" {
		body = overrideModel(body, r.Header, resp.DefaultModel)
	}

	// headSent survives across attempts: once a stream is open (because an attempt got a 200 and
	// only then hit an overload), the next account keeps writing into that same response.
	headSent := false
	for i, cand := range resp.Candidates {
		canRetry := i < len(resp.Candidates)-1
		res := h.forward(r.Context(), w, r, cand, resp.Candidates, i, resp.UserID, resp.TokenID, body, canRetry, headSent)
		res.report.Free = resp.Free
		h.ctrl.ReportUsage(context.Background(), res.report)
		headSent = res.headSent
		if !res.retry {
			return
		}
	}
}

// extractInboundToken reads the proxy token from x-api-key or Authorization: Bearer.
func extractInboundToken(r *http.Request) string {
	if v := strings.TrimSpace(r.Header.Get("x-api-key")); v != "" {
		return v
	}
	auth := r.Header.Get("Authorization")
	if auth == "" {
		return ""
	}
	return strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
}
