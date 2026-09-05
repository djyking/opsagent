#!/usr/bin/env bash
# Online backups: transactional MySQL dump plus attachment files, never model caches.
set -Eeuo pipefail
umask 077

readonly COMPOSE_DIR=/opt/opsagent/deploy/public
readonly BACKUP_ROOT=/opt/opsagent/backups
readonly OWNER_MARKER=opsagent-logical-backup-v1
readonly -a COMPOSE=(docker compose --project-directory "$COMPOSE_DIR" --env-file "$COMPOSE_DIR/secret.env" -f "$COMPOSE_DIR/compose.yaml")
readonly -a SERVICES=(mysql redis rabbitmq nacos sentinel elasticsearch qdrant ops-auth-app ops-ticket-app ops-knowledge-app ops-rag-app ops-platform-app ops-gateway-app reranker prometheus alertmanager grafana ops-web-app)
CURRENT_BACKUP=
BACKUP_COMPLETE=false

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*"; }

validate_backup_root() {
  [[ -d "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" ]] || return 1
  [[ "$(realpath -e -- "$BACKUP_ROOT")" == "$BACKUP_ROOT" ]]
}

validate_owned_backup() {
  local candidate=$1 resolved name marker
  validate_backup_root || return 1
  [[ -d "$candidate" && ! -L "$candidate" ]] || return 1
  resolved=$(realpath -e -- "$candidate") || return 1
  [[ "$(dirname -- "$resolved")" == "$BACKUP_ROOT" ]] || return 1
  name=$(basename -- "$resolved")
  [[ "$name" =~ ^opsagent-backup-[0-9]{8}T[0-9]{6}Z-[[:alnum:]]{8}$ ]] || return 1
  [[ -f "$resolved/.owner" && ! -L "$resolved/.owner" ]] || return 1
  marker=$(cat -- "$resolved/.owner")
  [[ "$marker" == "$OWNER_MARKER" ]]
}

remove_owned_backup() {
  local candidate=$1 resolved
  validate_owned_backup "$candidate" || { log "Refusing to remove an unverified backup path" >&2; return 1; }
  resolved=$(realpath -e -- "$candidate")
  # Only the verified direct child created by this script is eligible.
  rm -rf --one-file-system -- "$resolved"
}

cleanup_old_backups() {
  local candidates candidate
  validate_backup_root || return 1
  candidates=$(find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'opsagent-backup-*' -mtime +6 -print)
  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] || continue
    if validate_owned_backup "$candidate" && [[ -f "$candidate/.complete" && ! -L "$candidate/.complete" ]]; then
      log "Removing expired owned backup: $(basename -- "$candidate")"
      remove_owned_backup "$candidate"
    else
      log "Skipping a directory without valid ownership/completion markers"
    fi
  done <<< "$candidates"
}

check_health() {
  local ids_text states service state health
  local -a ids
  ids_text=$("${COMPOSE[@]}" ps --all --quiet "${SERVICES[@]}")
  mapfile -t ids <<< "$ids_text"
  [[ ${#ids[@]} -eq ${#SERVICES[@]} && -n "${ids[0]}" ]] || { log 'Expected all 18 services to exist' >&2; return 1; }
  states=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${ids[@]}")
  while IFS='|' read -r service state health; do
    if [[ "$state" != running || ( "$health" != healthy && "$health" != none ) ]]; then
      log "Service is not ready: $service ($state/$health)" >&2
      return 1
    fi
  done <<< "$states"
}

finish() {
  local code=$?
  trap - EXIT
  if [[ "$BACKUP_COMPLETE" != true && -n "$CURRENT_BACKUP" ]]; then
    remove_owned_backup "$CURRENT_BACKUP" || log 'Partial backup cleanup was refused; inspect manually' >&2
  fi
  if [[ $code -ne 0 ]]; then log 'Backup failed; see this service journal for the failing step' >&2; fi
  exit "$code"
}

main() {
  local command
  [[ $# -eq 0 ]] || { printf 'Usage: %s\n' "$0" >&2; return 2; }
  for command in docker gzip tar sha256sum flock realpath find mktemp; do command -v "$command" >/dev/null; done
  [[ -r "$COMPOSE_DIR/secret.env" && -f "$COMPOSE_DIR/compose.yaml" ]]
  [[ "$(realpath -m -- "$BACKUP_ROOT")" == "$BACKUP_ROOT" ]] || { log 'Unexpected backup-root symlink' >&2; return 1; }
  install -d -m 0700 -- "$BACKUP_ROOT"
  validate_backup_root
  exec 9>"$BACKUP_ROOT/.backup.lock"
  flock -n 9 || { log 'Another backup is already running' >&2; return 75; }
  trap finish EXIT
  "${COMPOSE[@]}" config --quiet
  check_health

  CURRENT_BACKUP=$(mktemp -d "$BACKUP_ROOT/opsagent-backup-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX")
  printf '%s\n' "$OWNER_MARKER" > "$CURRENT_BACKUP/.owner"
  validate_owned_backup "$CURRENT_BACKUP"
  log 'Creating an online single-transaction dump of five application databases'
  "${COMPOSE[@]}" exec -T mysql sh -c 'exec env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --quick --routines --events --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF --column-statistics=0 --default-character-set=utf8mb4 --databases ops_auth ops_ticket ops_knowledge ops_rag ops_platform' \
    | gzip -1 > "$CURRENT_BACKUP/mysql.sql.gz"

  log 'Archiving knowledge attachments without stopping the application'
  "${COMPOSE[@]}" exec -T ops-knowledge-app tar --numeric-owner -cf - -C /app/data/uploads . \
    | gzip -1 > "$CURRENT_BACKUP/knowledge-uploads.tgz"
  gzip -t "$CURRENT_BACKUP/mysql.sql.gz"
  tar -tzf "$CURRENT_BACKUP/knowledge-uploads.tgz" >/dev/null
  (
    cd "$CURRENT_BACKUP"
    sha256sum mysql.sql.gz knowledge-uploads.tgz > SHA256SUMS
    sha256sum --check --status SHA256SUMS
  )
  printf 'format=%s\ncreated_utc=%s\ndatabases=ops_auth,ops_ticket,ops_knowledge,ops_rag,ops_platform\n' \
    "$OWNER_MARKER" "$(date -u +%FT%TZ)" > "$CURRENT_BACKUP/manifest.txt"
  check_health
  date -u +%FT%TZ > "$CURRENT_BACKUP/.complete"
  BACKUP_COMPLETE=true
  cleanup_old_backups
  log "Backup verified: $CURRENT_BACKUP"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi
