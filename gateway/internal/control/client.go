// Package control is the HTTP client for the Kotlin service's private /internal/* control API.
// The gateway calls Resolve once per inbound request and ReportUsage after each attempt.
package control

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"time"
)

// Candidate is one ordered try-list entry with decrypted upstream auth headers.
type Candidate struct {
	AccountID          int               `json:"accountId"`
	Type               string            `json:"type"`
	DeviceID           string            `json:"deviceId"`
	AccountUUID        string            `json:"accountUuid"` // subscription's account uuid for metadata.user_id; "" for API keys / not yet known
	AuthHeaders        map[string]string `json:"authHeaders"`
	FiveHourResetEpoch int64             `json:"fiveHourResetEpoch"`
	WeeklyResetEpoch   int64             `json:"weeklyResetEpoch"`
}

// ResolveResp is the /internal/resolve response.
type ResolveResp struct {
	UserID        *int        `json:"userId"`
	TokenID       *int        `json:"tokenId"`
	OverLimit     bool        `json:"overLimit"`
	DailyLimitUSD *float64    `json:"dailyLimitUsd"`
	UsedUSD       *float64    `json:"usedUsd"`
	Candidates    []Candidate `json:"candidates"`
	// Routing only: the token's static system prompt, to inject ahead of client system content.
	SystemPrompt *string `json:"systemPrompt"`
	// Proxy only: the model the token forces onto every request, in Claude Code's notation
	// (a trailing "[1m]" asks for the 1M-context beta). Empty = the client's own choice.
	DefaultModel string `json:"defaultModel"`
	// Free marks a path that consumes no subscription quota (token counting, model listing).
	// Echoed back on the usage report so the service can keep zero-token successes out of stats.
	Free bool `json:"free"`
	// RetryAfterSeconds is set when Candidates is empty because every reachable account is
	// parked: seconds until the earliest one frees up. Zero = no account at all.
	RetryAfterSeconds int64 `json:"retryAfterSeconds"`
	// Client-side only: the id this resolve announced to the service, to be handed back to
	// EndSession when the request finishes. Not part of the wire response.
	SessionID string `json:"-"`
}

// UsageReport is one upstream attempt's outcome, posted to /internal/usage.
type UsageReport struct {
	AccountID  int    `json:"accountId"`
	UserID     *int   `json:"userId"`
	TokenID    *int   `json:"tokenId,omitempty"`
	Input      int64  `json:"input"`
	Output     int64  `json:"output"`
	CacheRead  int64  `json:"cacheRead"`
	CacheWrite int64  `json:"cacheWrite"`
	// CacheWrite1h is the 1-hour-TTL slice of CacheWrite, priced at 2× input instead of 1.25×.
	CacheWrite1h int64 `json:"cacheWrite1h,omitempty"`
	// Server-side tool calls Anthropic bills per invocation (web search) — token counts alone
	// do not cover them.
	WebSearchRequests int64 `json:"webSearchRequests,omitempty"`
	WebFetchRequests  int64 `json:"webFetchRequests,omitempty"`
	// Fast marks a response served in fast mode, a premium price tier on the same model.
	Fast             bool              `json:"fast,omitempty"`
	Status           int               `json:"status"`
	Model            *string           `json:"model"`
	RatelimitHeaders map[string]string `json:"ratelimitHeaders"`
	Source           string            `json:"source,omitempty"`
	// Free echoes the resolve's free-path flag (token counting, model listing): the service
	// leaves a successful zero-token attempt out of the statistics.
	Free bool `json:"free,omitempty"`
	// MCP tool invocations observed in the response (tool_use blocks named "mcp__…", plus
	// server-side `mcp_tool_use` blocks normalized to the same shape), by name.
	McpCalls map[string]int64 `json:"mcpCalls,omitempty"`
}

type resolveReq struct {
	Token     string `json:"token"`
	Method    string `json:"method"`
	Path      string `json:"path"`
	Source    string `json:"source,omitempty"`
	RequestID string `json:"requestId,omitempty"`
}

type sessionEndReq struct {
	RequestID string `json:"requestId"`
}

// newSessionID mints the id that ties a resolve to its EndSession. Randomness only has to avoid
// collisions between concurrent in-flight requests; if the entropy source fails we fall back to
// the clock, which is still unique enough for a liveness gauge.
func newSessionID() string {
	var b [12]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("t%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(b[:])
}

// Client talks to the service control API.
type Client struct {
	serviceURL    string
	internalToken string
	source        string
	http          *http.Client
}

// New builds a control-API client. The HTTP client has short timeouts appropriate for the
// control plane (never the streaming datapath).
func New(serviceURL, internalToken string) *Client {
	return &Client{
		serviceURL:    serviceURL,
		internalToken: internalToken,
		http:          &http.Client{Timeout: 10 * time.Second},
	}
}

// WithSource tags every resolve/usage call with a datapath source ("routing" for the
// OpenAI/Anthropic API gateways). The default (empty) is treated as "proxy" by the service,
// so the existing Claude Code gateway keeps its behavior unchanged.
func (c *Client) WithSource(source string) *Client {
	c.source = source
	return c
}

// Resolve turns a proxy token + request line into an ordered candidate list. The returned int
// is the HTTP status so the caller can map 401/403 straight to the client.
func (c *Client) Resolve(ctx context.Context, token, method, path string) (*ResolveResp, int, error) {
	sessionID := newSessionID()
	body, _ := json.Marshal(resolveReq{Token: token, Method: method, Path: path, Source: c.source, RequestID: sessionID})
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.serviceURL+"/internal/resolve", bytes.NewReader(body))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Token", c.internalToken)
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		// Drain so the connection can be reused; body is not needed on error.
		_, _ = io.Copy(io.Discard, resp.Body)
		return nil, resp.StatusCode, nil
	}
	var out ResolveResp
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, resp.StatusCode, fmt.Errorf("decode resolve: %w", err)
	}
	out.SessionID = sessionID
	return &out, resp.StatusCode, nil
}

// EndSession tells the service this request is done, closing the "active now" entry the resolve
// opened. Fire-and-forget and single-shot: a lost close only leaves an entry to age out server
// side, which is not worth retrying (or ever blocking the response path) for.
func (c *Client) EndSession(sessionID string) {
	if sessionID == "" {
		return
	}
	body, _ := json.Marshal(sessionEndReq{RequestID: sessionID})
	go func() {
		rc, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		req, err := http.NewRequestWithContext(rc, http.MethodPost, c.serviceURL+"/internal/session-end", bytes.NewReader(body))
		if err != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Internal-Token", c.internalToken)
		resp, err := c.http.Do(req)
		if err != nil {
			return
		}
		_, _ = io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
	}()
}

// ReportUsage posts an attempt outcome fire-and-forget: it runs in a goroutine with a couple of
// retries, then logs and drops. Usage accounting must never block or fail the datapath.
func (c *Client) ReportUsage(ctx context.Context, r UsageReport) {
	if r.Source == "" {
		r.Source = c.source
	}
	body, _ := json.Marshal(r)
	go func() {
		for attempt := 0; attempt < 3; attempt++ {
			rc, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			req, err := http.NewRequestWithContext(rc, http.MethodPost, c.serviceURL+"/internal/usage", bytes.NewReader(body))
			if err != nil {
				cancel()
				return
			}
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-Internal-Token", c.internalToken)
			resp, err := c.http.Do(req)
			cancel()
			if err == nil {
				_, _ = io.Copy(io.Discard, resp.Body)
				resp.Body.Close()
				if resp.StatusCode < 500 {
					return
				}
			}
		}
		log.Printf("control: usage report dropped after retries (acct#%d status=%d)", r.AccountID, r.Status)
	}()
}
