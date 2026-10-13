#!/usr/bin/env bash
# Run only inside an isolated container; it uses that container's /opt/opsagent/backups.
set -Eeuo pipefail
source "$(dirname "$0")/backup-daily.sh"
[[ "${OPSAGENT_RETENTION_TEST_ISOLATED:-}" == 1 ]] || { echo 'An isolated test container is required' >&2; exit 1; }
mkdir -p "$BACKUP_ROOT" /unrelated

create_owned() {
  mkdir "$1"
  printf '%s\n' "$OWNER_MARKER" > "$1/.owner"
  date -u +%FT%TZ > "$1/.complete"
}

old="$BACKUP_ROOT/opsagent-backup-20200101T000000Z-ABCDEFGH"
recent="$BACKUP_ROOT/opsagent-backup-20260101T000000Z-ABCDEFGH"
unowned="$BACKUP_ROOT/opsagent-backup-20200101T000000Z-UNOWNED1"
linked="$BACKUP_ROOT/opsagent-backup-20200101T000000Z-SYMLINK1"
create_owned "$old"
create_owned "$recent"
mkdir "$unowned"
touch -d '8 days ago' "$old" "$unowned"
ln -s /unrelated "$linked"
if remove_owned_backup "$unowned"; then echo 'Unowned deletion unexpectedly succeeded'; exit 1; fi
if remove_owned_backup "$linked"; then echo 'Symlink deletion unexpectedly succeeded'; exit 1; fi
if remove_owned_backup "$BACKUP_ROOT"; then echo 'Backup-root deletion unexpectedly succeeded'; exit 1; fi
cleanup_old_backups
[[ ! -e "$old" && -d "$recent" && -d "$unowned" && -L "$linked" && -d /unrelated ]]
echo 'Retention guard tests passed: expired owned backup removed; recent, unowned, root and symlink targets preserved.'

# Mock only Docker's metadata calls; no running service or database is contacted.
health_mode=healthy
docker() {
  local service
  if [[ "$1" == compose ]]; then
    for service in "${SERVICES[@]}"; do printf 'fake-%s\n' "$service"; done
  elif [[ "$1" == inspect ]]; then
    for service in "${SERVICES[@]}"; do printf '%s|running|%s\n' "$service" "$health_mode"; done
  else
    return 2
  fi
}
check_health
health_mode=unhealthy
if check_health; then echo 'Unhealthy services unexpectedly passed the backup gate'; exit 1; fi
echo 'Health gate test passed: an unhealthy service returns nonzero.'
