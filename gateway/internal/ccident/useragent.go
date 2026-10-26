package ccident

import (
	"regexp"
	"strconv"
	"sync/atomic"
)

// Anthropic gates newer models on the Claude Code version in the User-Agent: an old
// `claude-cli/x.y.z` is told to update Claude Code. Requests that never came from the CLI
// (routing) present a configured one, which goes stale with every CLI release. The proxy datapath
// relays the real CLI's user agent, so the newest one Anthropic accepted there is a version known
// to be current — routing borrows it once it is newer than the configured fallback.

var cliVersion = regexp.MustCompile(`^claude-cli/(\d+)\.(\d+)\.(\d+)`)

var observedUA atomic.Pointer[string]

// uaVersion parses the claude-cli version out of a user agent; ok is false for anything else.
func uaVersion(ua string) (v [3]int, ok bool) {
	m := cliVersion.FindStringSubmatch(ua)
	if m == nil {
		return v, false
	}
	for i := range v {
		v[i], _ = strconv.Atoi(m[i+1])
	}
	return v, true
}

func newer(a, b [3]int) bool {
	for i := range a {
		if a[i] != b[i] {
			return a[i] > b[i]
		}
	}
	return false
}

// ObserveUserAgent records a real Claude Code user agent that Anthropic just answered with a 2xx.
// Only after success: a client that spoofs a version Anthropic rejects never gets it adopted.
func ObserveUserAgent(ua string) {
	v, ok := uaVersion(ua)
	if !ok {
		return
	}
	for {
		cur := observedUA.Load()
		if cur != nil {
			if cv, _ := uaVersion(*cur); !newer(v, cv) {
				return
			}
		}
		if observedUA.CompareAndSwap(cur, &ua) {
			return
		}
	}
}

// UserAgent is the Claude Code user agent to present: the newest one observed on live traffic if
// it is ahead of [fallback] (the configured value), else [fallback].
func UserAgent(fallback string) string {
	cur := observedUA.Load()
	if cur == nil {
		return fallback
	}
	cv, _ := uaVersion(*cur)
	if fv, ok := uaVersion(fallback); ok && !newer(cv, fv) {
		return fallback
	}
	return *cur
}
