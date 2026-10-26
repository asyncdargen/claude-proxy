// Package models is the catalogue of Claude models the routing gateways advertise via their
// `/v1/models` endpoints (OpenAI-shaped and Anthropic-shaped). Under the hood every request is
// served by Claude through a subscription account, so these are the only real targets.
//
// The live list comes from the service (`/internal/models`: Anthropic's own listing under the
// operator's overrides) and is refreshed in the background by Keep; Fallback stands in until the
// first fetch succeeds, so a service hiccup at startup never advertises an empty list.
package models

import (
	"context"
	"log"
	"strings"
	"sync/atomic"
	"time"
)

// Model is one advertised Claude model.
type Model struct {
	ID          string
	DisplayName string
	// CreatedAt is Anthropic's RFC3339 release stamp, empty when unknown.
	CreatedAt string
}

// Fallback is advertised until the service answers, newest/most-capable first. Any other valid
// claude-* id also works regardless, since the gateway forwards the model verbatim.
var Fallback = []Model{
	{ID: "claude-opus-5-5", DisplayName: "Claude Opus 5.5"},
	{ID: "claude-opus-5", DisplayName: "Claude Opus 5"},
	{ID: "claude-sonnet-5-5", DisplayName: "Claude Sonnet 5.5"},
	{ID: "claude-sonnet-5", DisplayName: "Claude Sonnet 5"},
	{ID: "claude-fable-5-1", DisplayName: "Claude Fable 5.1"},
	{ID: "claude-fable-5", DisplayName: "Claude Fable 5"},
	{ID: "claude-opus-4-8", DisplayName: "Claude Opus 4.8"},
	{ID: "claude-sonnet-4-6", DisplayName: "Claude Sonnet 4.6"},
	{ID: "claude-haiku-4-5-20251001", DisplayName: "Claude Haiku 4.5"},
}

var current atomic.Pointer[[]Model]

// List returns the models to advertise right now.
func List() []Model {
	if p := current.Load(); p != nil {
		return *p
	}
	return Fallback
}

// Set replaces the advertised list. An empty list is ignored: the operator hiding every model is
// far less likely than a broken fetch, and an empty /v1/models breaks client model pickers.
func Set(ms []Model) {
	if len(ms) == 0 {
		return
	}
	cp := append([]Model(nil), ms...)
	current.Store(&cp)
}

// Find looks an id up in the advertised list.
func Find(id string) (Model, bool) {
	for _, m := range List() {
		if m.ID == id {
			return m, true
		}
	}
	return Model{}, false
}

// Newest returns the first advertised id containing family ("opus", "haiku"), else def. The list
// is newest-first, so an OpenAI client asking for "an opus" gets the current one.
func Newest(family, def string) string {
	for _, m := range List() {
		if strings.Contains(m.ID, family) {
			return m.ID
		}
	}
	return def
}

// Keep refreshes the list every interval until ctx ends. Failures keep the previous list.
func Keep(ctx context.Context, fetch func(context.Context) ([]Model, error), every time.Duration) {
	for {
		fctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		ms, err := fetch(fctx)
		cancel()
		if err != nil {
			log.Printf("models: refresh failed, keeping the previous list: %v", err)
		} else {
			Set(ms)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(every):
		}
	}
}

// Created is a fixed epoch-seconds "created" timestamp for the OpenAI model list when the real
// release date is unknown (clients only check presence/shape).
const Created int64 = 1735689600 // 2025-01-01T00:00:00Z

// CreatedAt is the Anthropic `created_at` RFC3339 stamp used when the real one is unknown.
const CreatedAt = "2025-01-01T00:00:00Z"

// CreatedAtOrDefault is m's release stamp, or the constant when unknown.
func (m Model) CreatedAtOrDefault() string {
	if m.CreatedAt != "" {
		return m.CreatedAt
	}
	return CreatedAt
}

// CreatedUnix is m's release date in epoch seconds, or Created when unknown or unparseable.
func (m Model) CreatedUnix() int64 {
	if t, err := time.Parse(time.RFC3339, m.CreatedAt); err == nil {
		return t.Unix()
	}
	return Created
}
