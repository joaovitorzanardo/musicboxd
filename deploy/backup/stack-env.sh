# deploy/backup/stack-env.sh - sourced (from deploy/) by backup-db.sh and restore-check.sh. Reads BACKUP_BUCKET
# and AWS_REGION from $ENV_FILE the way compose's dotenv parser does (inline " # comment", trailing spaces
# and CR dropped) and validates them. Exits 2 on bad config.
env_get() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -1 | sed -E 's/\r$//; s/[[:space:]]+#.*$//; s/[[:space:]]+$//'; }
BUCKET=$(env_get BACKUP_BUCKET)
REGION=$(env_get AWS_REGION)
[[ -n $BUCKET && -n $REGION ]] || { echo "BACKUP_BUCKET and AWS_REGION must be set in $ENV_FILE" >&2; exit 2; }
[[ $BUCKET =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || { echo "Invalid BACKUP_BUCKET '$BUCKET'" >&2; exit 2; }
[[ $REGION =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || { echo "Invalid AWS_REGION '$REGION'" >&2; exit 2; }
