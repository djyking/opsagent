#!/bin/sh
set -eu
# A process lifetime identity, regenerated only when this JVM starts.
instance_id="$(cat /proc/sys/kernel/random/uuid)"
export OPS_RUNTIME_INSTANCE_ID="$instance_id"
export OTEL_RESOURCE_ATTRIBUTES="${OTEL_RESOURCE_ATTRIBUTES},service.instance.id=${instance_id},host.name=$(hostname),opsagent.runtime.kind=DOCKER_COMPOSE"
exec java -jar /app/app.jar
