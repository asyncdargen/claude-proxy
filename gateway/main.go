// Command gateway is claude-proxy's Go data plane: one binary, one container, one listener.
//
// It serves three things that were once three deployments but have always been one codebase:
//
//	/v1/…                  the Claude Code datapath (nginx strips the public /gateway prefix)
//	/routing/openai/…      the OpenAI Chat Completions gateway
//	/routing/anthropic/…   the native Anthropic Messages gateway
//
// They differ only in the translator in front and the usage `source` they report; everything
// underneath — account resolution, upstream forwarding, SSE relay, usage reporting — is shared.
// All state lives in the Kotlin service, reached over its private /internal/* control API; the
// gateway never touches the database.
package main

import (
	"log"
	"net/http"
	"time"

	"claudeproxy/gateway/internal/anthropicgw"
	"claudeproxy/gateway/internal/config"
	"claudeproxy/gateway/internal/control"
	"claudeproxy/gateway/internal/egress"
	"claudeproxy/gateway/internal/openaigw"
	"claudeproxy/gateway/internal/proxy"
	"claudeproxy/gateway/internal/routing"
)

// Public path prefixes the routing gateways are mounted under. nginx forwards these verbatim —
// its proxy_pass for them deliberately carries no URI part — so the prefix is stripped here.
// Each translator then sees the native path it expects (/v1/chat/completions, /v1/messages).
const (
	openAIPrefix    = "/routing/openai"
	anthropicPrefix = "/routing/anthropic"
)

// newMux wires every route. Split out of main so the path routing is testable.
func newMux(cfg *config.Config) *http.ServeMux {
	ctrl := control.New(cfg.ServiceURL, cfg.InternalToken)
	// Routing spend is metered against its own per-user daily limit, so it resolves under a
	// different source tag than the Claude Code datapath.
	routingCtrl := control.New(cfg.ServiceURL, cfg.InternalToken).WithSource("routing")

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"message":"ok"}`))
	})
	mux.Handle(openAIPrefix+"/",
		http.StripPrefix(openAIPrefix, routing.NewHandler(cfg, routingCtrl, openaigw.New())))
	mux.Handle(anthropicPrefix+"/",
		http.StripPrefix(anthropicPrefix, routing.NewHandler(cfg, routingCtrl, anthropicgw.New())))
	// Registered last and least specific: the datapath owns everything else. ServeMux prefers the
	// longest matching pattern, so the routing prefixes above win over this.
	mux.Handle("/", proxy.NewHandler(cfg, ctrl))
	return mux
}

func main() {
	cfg := config.Load()
	if _, err := egress.ParseProxy(cfg.AnthropicProxyURL); err != nil {
		log.Fatal(err)
	}
	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           newMux(cfg),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("gateway on :%s -> %s (service %s); routing mounted at %s/ and %s/",
		cfg.Port, cfg.UpstreamBaseURL, cfg.ServiceURL, openAIPrefix, anthropicPrefix)
	log.Fatal(srv.ListenAndServe())
}
