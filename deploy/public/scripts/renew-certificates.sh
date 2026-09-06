#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

source "$(dirname -- "${BASH_SOURCE[0]}")/compose-command.sh"
opsagent_compose_init /opt/opsagent/deploy/public
opsagent_trace_artifacts
readonly COMPOSE_DIR
readonly -a COMPOSE
dry_run=false
case "${1:-}" in
  '') [[ $# -eq 0 ]] ;;
  --dry-run) [[ $# -eq 1 ]]; dry_run=true ;;
  *) printf 'Usage: %s [--dry-run]\n' "$0" >&2; exit 2 ;;
esac

[[ -r "$COMPOSE_DIR/secret.env" ]]
exec 9>/run/lock/opsagent-cert-renew.lock
flock -n 9 || { echo 'Another certificate renewal is already running' >&2; exit 75; }
"${COMPOSE[@]}" config --quiet
web_id=$("${COMPOSE[@]}" ps --all --quiet ops-web-app)
[[ -n "$web_id" ]]
web_health=$(docker inspect --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$web_id")
[[ "$web_health" == running/healthy ]] || { echo 'The public web container is not healthy' >&2; exit 1; }
"${COMPOSE[@]}" exec -T ops-web-app test -s /etc/letsencrypt/live/opsagent.cloud/fullchain.pem
"${COMPOSE[@]}" exec -T ops-web-app test -s /etc/letsencrypt/live/opsagent.cloud/privkey.pem
"${COMPOSE[@]}" exec -T ops-web-app nginx -t
renew_args=(renew --webroot -w /var/www/certbot --quiet --no-random-sleep-on-renew)
if [[ "$dry_run" == true ]]; then renew_args+=(--dry-run); fi
"${COMPOSE[@]}" --profile tls run --rm --no-deps certbot "${renew_args[@]}"
"${COMPOSE[@]}" exec -T ops-web-app nginx -t
if [[ "$dry_run" != true ]]; then
  "${COMPOSE[@]}" exec -T ops-web-app nginx -s reload
fi
