package models

import (
	"strconv"
	"strings"
)

// Request-surface differences between model generations. The OpenAI translator builds Anthropic
// bodies from scratch, so it has to leave out what a given model answers with a 400 — the client
// asked for an OpenAI request that is perfectly valid on its own terms.

// version splits "claude-<family>-<major>[-<minor>][-<date>]" into its parts. A trailing date
// ("claude-opus-4-20250514") is not a minor version. ok is false for anything else.
func version(id string) (family string, major, minor int, ok bool) {
	parts := strings.Split(strings.ToLower(id), "-")
	if len(parts) < 3 || parts[0] != "claude" {
		return "", 0, 0, false
	}
	family = parts[1]
	if _, err := strconv.Atoi(family); err == nil {
		return "", 0, 0, false // "claude-3-5-sonnet-…": the old naming, long before any of this
	}
	major, err := strconv.Atoi(parts[2])
	if err != nil || major > 99 {
		return "", 0, 0, false
	}
	if len(parts) > 3 && len(parts[3]) <= 2 {
		minor, _ = strconv.Atoi(parts[3])
	}
	return family, major, minor, true
}

// SamplingParams reports whether the model still accepts temperature/top_p. Removed from Opus 4.7
// on and from every 5-generation model; unknown ids keep them, as before.
func SamplingParams(id string) bool {
	family, major, minor, ok := version(id)
	if !ok {
		return true
	}
	if major >= 5 {
		return false
	}
	return !(family == "opus" && major == 4 && minor >= 7)
}

// ForcedToolChoice reports whether the model accepts tool_choice "any"/"tool". Opus 5.5,
// Sonnet 5.5 and Fable/Mythos 5.1 reject both with a 400; "auto" and "none" still work.
func ForcedToolChoice(id string) bool {
	family, major, minor, ok := version(id)
	if !ok {
		return true
	}
	switch family {
	case "opus", "sonnet":
		return major < 5 || (major == 5 && minor < 5)
	case "fable", "mythos":
		return major < 5 || (major == 5 && minor < 1)
	}
	return major < 6
}
