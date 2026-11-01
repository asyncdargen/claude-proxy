# Image build definition for every claude-proxy image.
#   docker buildx bake -f docker-bake.hcl --print                     # show the resolved build
#   PLATFORMS=linux/arm64 docker buildx bake -f docker-bake.hcl --load # local, native-arch build
#   scripts/release-images.sh                        # multi-arch build + push to GHCR
# Always pass -f docker-bake.hcl: without it bake also merges docker-compose.yml (which needs a .env).
# Variables come from the environment (VERSION=0.2.0 docker buildx bake …).

variable "REGISTRY" {
  default = "ghcr.io/asyncdargen"
}

# The moving tag every build gets (latest, or e.g. "edge" for a branch build).
variable "TAG" {
  default = "latest"
}

# Release version (e.g. 0.2.0) — adds a :<VERSION> tag when set.
variable "VERSION" {
  default = ""
}

# Git commit sha — adds a :sha-<short> tag and the revision label when set.
variable "REVISION" {
  default = ""
}

# Repository the packages belong to. GitHub links a container package to its repo from
# org.opencontainers.image.source — and for a multi-arch image it reads that from the *index*
# manifest's annotations, not from the per-platform image labels (which the Dockerfiles set too).
variable "SOURCE" {
  default = "https://github.com/asyncdargen/claude-proxy"
}

variable "PLATFORMS" {
  default = "linux/amd64,linux/arm64"
}

function "tags" {
  params = [name]
  result = compact([
    "${REGISTRY}/${name}:${TAG}",
    notequal(VERSION, "") ? "${REGISTRY}/${name}:${VERSION}" : "",
    notequal(REVISION, "") ? "${REGISTRY}/${name}:sha-${substr(REVISION, 0, 7)}" : "",
  ])
}

function "annotations" {
  params = [description]
  result = [
    "index,manifest:org.opencontainers.image.source=${SOURCE}",
    "index,manifest:org.opencontainers.image.description=${description}",
    "index,manifest:org.opencontainers.image.licenses=MIT",
  ]
}

group "default" {
  targets = ["service", "gateway", "web", "allinone"]
}

target "_common" {
  context   = "."
  platforms = split(",", PLATFORMS)
  args = {
    VERSION  = notequal(VERSION, "") ? VERSION : "dev"
    REVISION = notequal(REVISION, "") ? REVISION : "unknown"
  }
}

target "service" {
  inherits   = ["_common"]
  dockerfile = "deploy/Dockerfile.service"
  tags       = tags("claude-proxy-service")
  annotations = annotations("claude-proxy Kotlin service: management API, control API, Kotlin datapath")
}

target "gateway" {
  inherits   = ["_common"]
  dockerfile = "deploy/Dockerfile.gateway"
  tags       = tags("claude-proxy-gateway")
  annotations = annotations("claude-proxy Go data plane: Claude Code datapath + OpenAI/Anthropic routing gateways")
}

target "web" {
  inherits   = ["_common"]
  dockerfile = "deploy/Dockerfile.web"
  tags       = tags("claude-proxy-web")
  annotations = annotations("claude-proxy admin/chat UI and edge router (nginx)")
}

target "allinone" {
  inherits   = ["_common"]
  dockerfile = "deploy/Dockerfile.allinone"
  tags       = tags("claude-proxy")
  annotations = annotations("claude-proxy all-in-one: service + gateway + web UI in one container")
}
