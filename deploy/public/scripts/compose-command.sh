#!/usr/bin/env bash
# Shared selection for cloud lifecycle commands. Source presence never enables Trace.

opsagent_compose_init() {
  local directory=$1 explicit_trace=${2:-false} marker
  COMPOSE_DIR=$(realpath -m -- "$directory")
  marker="$COMPOSE_DIR/runtime/observability-v3.enabled"
  OPSAGENT_TRACE_ACTIVE=false
  if [[ -e "$marker" || -L "$marker" ]]; then
    [[ -f "$marker" && ! -L "$marker" && "$(cat -- "$marker")" == opsagent-observability-v3-verified-v1 ]] || {
      echo 'Invalid Trace enablement marker; refusing to silently use base Compose.' >&2; return 1;
    }
    OPSAGENT_TRACE_ACTIVE=true
  fi
  [[ "$explicit_trace" == false || "$explicit_trace" == true ]] || return 2
  if [[ "$explicit_trace" == true ]]; then OPSAGENT_TRACE_ACTIVE=true; fi
  COMPOSE=(docker compose --project-directory "$COMPOSE_DIR" --env-file "$COMPOSE_DIR/secret.env" -f "$COMPOSE_DIR/compose.yaml")
  if [[ "$OPSAGENT_TRACE_ACTIVE" == true ]]; then
    [[ -f "$COMPOSE_DIR/compose.observability-v3.yaml" ]] || {
      echo 'Trace is enabled but its Compose overlay is missing; deployment is blocked.' >&2; return 1;
    }
    COMPOSE+=(-f "$COMPOSE_DIR/compose.observability-v3.yaml")
  fi
}

opsagent_trace_artifacts() {
  [[ "$OPSAGENT_TRACE_ACTIVE" == true ]] || return 0
  local checksum jar="$COMPOSE_DIR/otel/opentelemetry-javaagent-2.31.1.jar"
  [[ -s "$jar" && -s "$COMPOSE_DIR/config/otel/java-entrypoint.sh" && -s "$COMPOSE_DIR/config/otel/collector.yaml" && -s "$COMPOSE_DIR/config/otel/tempo.yaml" ]] || {
    echo 'Required Trace runtime artifacts are missing; refusing partial deployment.' >&2; return 1;
  }
  checksum=$(sed -n 's/^opentelemetry-javaagent.sha256=//p' "$COMPOSE_DIR/config/otel/versions.lock" | tr -d '\r')
  [[ "$checksum" =~ ^[a-f0-9]{64}$ ]] || return 1
  printf '%s  %s\n' "$checksum" "$jar" | sha256sum --check --status -
}
