#!/usr/bin/env bash
# One cloud Compose entry point for initial setup, deployment, backup and restore.
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/compose-command.sh"
directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
explicit_trace=false
record=false
case "${1:-}" in
  --trace) explicit_trace=true; shift ;;
  --record-trace-enabled) explicit_trace=true; record=true; shift ;;
esac
opsagent_compose_init "$directory" "$explicit_trace"
opsagent_trace_artifacts

if [[ "$record" == true ]]; then
  [[ $# == 0 ]] || { echo 'Usage: compose.sh --record-trace-enabled' >&2; exit 2; }
  "${COMPOSE[@]}" config --quiet
  for service in ops-demo-order-app ops-platform-app ops-agent-app ops-rag-app ops-gateway-app; do
    id=$("${COMPOSE[@]}" ps --quiet "$service")
    [[ -n "$id" && "$id" != *$'\n'* ]] || { echo "Expected one active Trace instance: $service" >&2; exit 1; }
    state=$(docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{json .Config.Entrypoint}}' "$id")
    [[ "$state" == 'running|healthy|["/bin/sh","/opt/otel/java-entrypoint.sh"]' ]] || {
      echo "Trace JVM is not active and healthy: $service" >&2; exit 1;
    }
    files=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$id")
    [[ ",$files," == *",$COMPOSE_DIR/compose.observability-v3.yaml,"* ]] || {
      echo "Running instance lacks the Trace overlay: $service" >&2; exit 1;
    }
  done
  "${COMPOSE[@]}" exec -T ops-demo-order-app curl -fsS --connect-timeout 3 --max-time 5 -o /dev/null http://otel-collector:13133/
  "${COMPOSE[@]}" exec -T ops-demo-order-app curl -fsS --connect-timeout 3 --max-time 5 -o /dev/null http://tempo:3200/ready
  [[ ! -L "$COMPOSE_DIR/runtime" && ! -L "$COMPOSE_DIR/runtime/observability-v3.enabled" ]] || exit 1
  mkdir -p -- "$COMPOSE_DIR/runtime"
  umask 077
  temporary=$(mktemp "$COMPOSE_DIR/runtime/.observability-v3.XXXXXXXX")
  trap 'rm -f -- "$temporary"' EXIT
  printf '%s\n' opsagent-observability-v3-verified-v1 > "$temporary"
  mv -f -- "$temporary" "$COMPOSE_DIR/runtime/observability-v3.enabled"
  trap - EXIT
  echo 'Trace runtime verified; subsequent cloud lifecycle commands retain its overlay.'
  exit 0
fi

[[ $# -gt 0 ]] || { echo 'Usage: compose.sh [--trace] <compose arguments> | --record-trace-enabled' >&2; exit 2; }
arguments=("$@")
index=0
while (( index < ${#arguments[@]} )); do
  case "${arguments[index]}" in
    -f*|--file|--file=*|--project-directory|--project-directory=*|--env-file|--env-file=*)
      echo 'Compose files and environment are fixed by the cloud wrapper.' >&2; exit 2 ;;
    --ansi|--parallel|--profile|--progress|--project-name|-p)
      (( index + 1 < ${#arguments[@]} )) || { echo 'Missing Compose option value.' >&2; exit 2; }
      index=$((index + 2)) ;;
    --) break ;;
    -*) index=$((index + 1)) ;;
    *) break ;;
  esac
done
exec "${COMPOSE[@]}" "$@"
