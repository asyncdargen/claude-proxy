package ccident

import "testing"

func TestUserAgentFollowsNewestObserved(t *testing.T) {
	observedUA.Store(nil)
	defer observedUA.Store(nil)
	const fallback = "claude-cli/2.1.292 (external, cli)"

	if got := UserAgent(fallback); got != fallback {
		t.Fatalf("nothing observed: got %q", got)
	}
	ObserveUserAgent("claude-cli/2.1.200 (external, cli)")
	if got := UserAgent(fallback); got != fallback {
		t.Fatalf("an older observed version must not win: got %q", got)
	}
	ObserveUserAgent("claude-cli/2.1.300 (external, sdk-cli)")
	ObserveUserAgent("claude-cli/2.1.299 (external, cli)")
	ObserveUserAgent("curl/8.0")
	if got := UserAgent(fallback); got != "claude-cli/2.1.300 (external, sdk-cli)" {
		t.Fatalf("newest observed must win: got %q", got)
	}
	ObserveUserAgent("claude-cli/2.10.0 (external, cli)")
	if got := UserAgent(fallback); got != "claude-cli/2.10.0 (external, cli)" {
		t.Fatalf("versions compare numerically: got %q", got)
	}
}
