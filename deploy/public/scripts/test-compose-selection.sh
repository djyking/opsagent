#!/usr/bin/env bash
# Pure command/marker tests. No Docker, network or application is contacted.
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/compose-command.sh"
[[ $# == 1 && -d "$1" ]] || { echo 'Provide an existing ignored scratch directory.' >&2; exit 2; }
fixture=$(mktemp -d "$(realpath -- "$1")/compose-selection.XXXXXXXX")
mkdir -p "$fixture/runtime" "$fixture/otel" "$fixture/config/otel"
printf 'name: opsagent\n' > "$fixture/compose.yaml"
printf 'services: {}\n' > "$fixture/compose.observability-v3.yaml"

opsagent_compose_init "$fixture"
[[ "$OPSAGENT_TRACE_ACTIVE" == false && ${#COMPOSE[@]} == 8 ]]
echo 'PASS overlay source alone does not activate Trace'
opsagent_compose_init "$fixture" true
[[ "$OPSAGENT_TRACE_ACTIVE" == true && ${#COMPOSE[@]} == 10 ]]
[[ "${COMPOSE[9]}" == "$fixture/compose.observability-v3.yaml" ]]
echo 'PASS explicit activation selects both files'
printf 'opsagent-observability-v3-verified-v1\n' > "$fixture/runtime/observability-v3.enabled"
opsagent_compose_init "$fixture"
[[ "$OPSAGENT_TRACE_ACTIVE" == true && ${#COMPOSE[@]} == 10 ]]
echo 'PASS verified runtime marker retains Trace for later commands'
printf 'invalid-marker\n' > "$fixture/runtime/observability-v3.enabled"
if opsagent_compose_init "$fixture" 2>/dev/null; then echo 'Invalid marker accepted' >&2; exit 1; fi
printf 'opsagent-observability-v3-verified-v1\n' > "$fixture/runtime/observability-v3.enabled"
mv "$fixture/compose.observability-v3.yaml" "$fixture/overlay.saved"
if opsagent_compose_init "$fixture" 2>/dev/null; then echo 'Missing active overlay accepted' >&2; exit 1; fi
mv "$fixture/overlay.saved" "$fixture/compose.observability-v3.yaml"
opsagent_compose_init "$fixture"
if opsagent_trace_artifacts 2>/dev/null; then echo 'Missing Java agent accepted' >&2; exit 1; fi
printf 'fixture-agent\n' > "$fixture/otel/opentelemetry-javaagent-2.31.1.jar"
for name in java-entrypoint.sh collector.yaml tempo.yaml; do printf 'fixture\n' > "$fixture/config/otel/$name"; done
checksum=$(sha256sum "$fixture/otel/opentelemetry-javaagent-2.31.1.jar"); checksum=${checksum%% *}
printf 'opentelemetry-javaagent.sha256=%s\n' "$checksum" > "$fixture/config/otel/versions.lock"
opsagent_trace_artifacts
printf 'modified\n' >> "$fixture/otel/opentelemetry-javaagent-2.31.1.jar"
if opsagent_trace_artifacts 2>/dev/null; then echo 'Changed Java agent accepted' >&2; exit 1; fi
echo 'PASS invalid marker, missing overlay/artifact and changed agent checksum fail closed'

# A fake Docker executable verifies CLI argument forwarding without contacting Docker.
mkdir -p "$fixture/scripts" "$fixture/bin"
cp "$(dirname -- "${BASH_SOURCE[0]}")/compose.sh" "$(dirname -- "${BASH_SOURCE[0]}")/compose-command.sh" "$fixture/scripts/"
printf 'fixture-agent\n' > "$fixture/otel/opentelemetry-javaagent-2.31.1.jar"
mv "$fixture/runtime/observability-v3.enabled" "$fixture/runtime/marker.saved"
cat > "$fixture/bin/docker" <<'FAKE'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$@" > "$OPSAGENT_COMPOSE_TEST_ARGUMENTS"
if [[ "${OPSAGENT_COMPOSE_TEST_MODE:-forward}" == record ]]; then
  if [[ "$1" == inspect && "$3" == *Entrypoint* ]]; then
    printf '%s\n' 'running|healthy|["/bin/sh","/opt/otel/java-entrypoint.sh"]'
  elif [[ "$1" == inspect ]]; then
    printf '%s\n' "$OPSAGENT_COMPOSE_TEST_ROOT/compose.yaml,$OPSAGENT_COMPOSE_TEST_ROOT/compose.observability-v3.yaml"
  elif [[ "$*" == *'ps --quiet'* ]]; then
    printf 'fixture-id\n'
  fi
fi
FAKE
chmod +x "$fixture/bin/docker"
export OPSAGENT_COMPOSE_TEST_ARGUMENTS="$fixture/arguments.log"
export OPSAGENT_COMPOSE_TEST_ROOT="$fixture"
export PATH="$fixture/bin:$PATH"
for command in attach bridge build commit config cp create down events exec images kill logs ls pause port ps publish pull push restart rm run scale start stats stop top unpause up version wait watch; do
  bash "$fixture/scripts/compose.sh" "$command" --application-option 'value with spaces' -fsS --env-file app.env
  grep -Fx -- "$command" "$fixture/arguments.log" >/dev/null
  grep -Fx -- '-fsS' "$fixture/arguments.log" >/dev/null
  grep -Fx -- 'value with spaces' "$fixture/arguments.log" >/dev/null
  if grep -Fx -- "$fixture/compose.observability-v3.yaml" "$fixture/arguments.log" >/dev/null; then exit 1; fi
done
bash "$fixture/scripts/compose.sh" --profile tls run --rm certbot -f
grep -Fx -- '-f' "$fixture/arguments.log" >/dev/null
if bash "$fixture/scripts/compose.sh" --profile tls -f other.yaml up 2>/dev/null; then exit 1; fi
bash "$fixture/scripts/compose.sh" --trace exec demo curl -fsS
grep -Fx -- "$fixture/compose.observability-v3.yaml" "$fixture/arguments.log" >/dev/null
if bash "$fixture/scripts/compose.sh" --record-trace-enabled extra 2>/dev/null; then exit 1; fi
[[ ! -e "$fixture/runtime/observability-v3.enabled" ]]
OPSAGENT_COMPOSE_TEST_MODE=record bash "$fixture/scripts/compose.sh" --record-trace-enabled
[[ "$(cat "$fixture/runtime/observability-v3.enabled")" == opsagent-observability-v3-verified-v1 ]]
bash "$fixture/scripts/compose.sh" up -d
grep -Fx -- "$fixture/compose.observability-v3.yaml" "$fixture/arguments.log" >/dev/null
echo 'PASS public subcommand arguments, profile options, explicit Trace and verified marker interface'
echo "Evidence fixture retained: $fixture"
