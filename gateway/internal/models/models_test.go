package models

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestListFollowsSetAndIgnoresEmpty(t *testing.T) {
	defer current.Store(nil)
	if List()[0].ID != Fallback[0].ID {
		t.Fatalf("before any fetch the fallback is advertised")
	}
	Set([]Model{{ID: "claude-opus-6", DisplayName: "Claude Opus 6"}, {ID: "claude-haiku-6"}})
	Set(nil) // a broken fetch must not empty the list
	if got := List(); len(got) != 2 || got[0].ID != "claude-opus-6" {
		t.Fatalf("List() = %v", got)
	}
	if Newest("opus", "x") != "claude-opus-6" || Newest("sonnet", "def") != "def" {
		t.Fatalf("Newest picks the first family match, else the default")
	}
	if _, ok := Find("claude-haiku-6"); !ok {
		t.Fatalf("Find misses a listed id")
	}
}

func TestKeepKeepsPreviousListOnError(t *testing.T) {
	defer current.Store(nil)
	Set([]Model{{ID: "claude-a"}})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		Keep(ctx, func(context.Context) ([]Model, error) { return nil, errors.New("down") }, time.Hour)
		close(done)
	}()
	time.Sleep(20 * time.Millisecond)
	cancel()
	<-done
	if List()[0].ID != "claude-a" {
		t.Fatalf("a failed refresh replaced the list: %v", List())
	}
}

func TestCreatedStamps(t *testing.T) {
	m := Model{ID: "x", CreatedAt: "2026-09-01T00:00:00Z"}
	if m.CreatedUnix() != 1788220800 || m.CreatedAtOrDefault() != "2026-09-01T00:00:00Z" {
		t.Fatalf("real stamp not used: %d", m.CreatedUnix())
	}
	if (Model{}).CreatedUnix() != Created || (Model{}).CreatedAtOrDefault() != CreatedAt {
		t.Fatalf("unknown stamp must fall back to the constants")
	}
}
