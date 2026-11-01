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
}

target "gateway" {
  inherits   = ["_common"]
  dockerfile = "deploy/Dockerfile.gateway"
  tags       = tags("claude-proxy-gateway")
}

target "web" {
  inherits   = ["_common"]
  dockerfile = "deploy/Dockerfile.web"
  tags       = tags("claude-proxy-web")
}

target "allinone" {
  inherits   = ["_common"]
  dockerfile = "deploy/Dockerfile.allinone"
  tags       = tags("claude-proxy")
}
