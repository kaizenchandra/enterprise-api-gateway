#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check_migrations.py
bash scripts/init-env.sh
export SOURCE_REVISION="$(python3 scripts/source_fingerprint.py)"
docker compose --env-file docker/.env -f docker/compose.yml build "$@"
printf 'Built source revision %s\n' "$SOURCE_REVISION"
