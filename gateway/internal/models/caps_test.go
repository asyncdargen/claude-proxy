package models

import "testing"

func TestSamplingParams(t *testing.T) {
	for id, want := range map[string]bool{
		"claude-opus-5-5":            false,
		"claude-opus-5":              false,
		"claude-opus-4-7":            false,
		"claude-opus-4-8":            false,
		"claude-sonnet-5-5":          false,
		"claude-fable-5-1":           false,
		"claude-opus-4-6":            true,
		"claude-sonnet-4-6":          true,
		"claude-opus-4-20250514":     true,
		"claude-haiku-4-5-20251001":  true,
		"claude-3-5-sonnet-20241022": true,
		"gpt-4o":                     true,
	} {
		if got := SamplingParams(id); got != want {
			t.Errorf("SamplingParams(%q) = %v, want %v", id, got, want)
		}
	}
}

func TestForcedToolChoice(t *testing.T) {
	for id, want := range map[string]bool{
		"claude-opus-5-5":           false,
		"claude-sonnet-5-5":         false,
		"claude-fable-5-1":          false,
		"claude-mythos-5-1":         false,
		"claude-opus-5":             true,
		"claude-sonnet-5":           true,
		"claude-fable-5":            true,
		"claude-opus-4-8":           true,
		"claude-haiku-4-5-20251001": true,
	} {
		if got := ForcedToolChoice(id); got != want {
			t.Errorf("ForcedToolChoice(%q) = %v, want %v", id, got, want)
		}
	}
}
