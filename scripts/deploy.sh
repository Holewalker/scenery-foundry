#!/usr/bin/env bash
# ADR-0008: VPS bootstrap for the prod-profile deployable stack (compose.yml is the deployable
# base — D11). Mirrors the sibling 000Libre project's scripts/deploy.sh shape (validate .env,
# `up --profile prod --build`, report the HTTPS URL); deliberately omits its host-crontab
# install, because the backup schedule runs inside the `backup` Compose sidecar instead (D10).
set -euo pipefail

repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"

if [ ! -f .env ]; then
  echo "==> No .env found. Copying .env.example -> .env"
  cp .env.example .env
  echo ""
  echo "    Edit .env with real values, then re-run this script:"
  echo "      nano .env"
  echo "      ./scripts/deploy.sh"
  echo ""
  echo "    Required for the prod profile: POSTGRES_PASSWORD, WORKER_DB_PASSWORD, DOMAIN,"
  echo "    DUCKDNS_SUBDOMAIN, DUCKDNS_TOKEN. See .env.example for the full list."
  exit 0
fi

required_vars="POSTGRES_PASSWORD WORKER_DB_PASSWORD DOMAIN DUCKDNS_SUBDOMAIN DUCKDNS_TOKEN"
missing=""
for var in $required_vars; do
  value="$(grep -E "^${var}=" .env | tail -1 | cut -d= -f2- || true)"
  [ -n "$value" ] || missing="$missing $var"
done
if [ -n "$missing" ]; then
  echo "==> Missing required .env value(s):$missing" >&2
  echo "    Edit .env and set them, then re-run this script." >&2
  exit 1
fi

# Secrets live in .env; keep it unreadable to other users on a shared VPS (ADR-0008).
current_perms="$(stat -c%a .env 2>/dev/null || stat -f%Lp .env 2>/dev/null || echo "")"
if [ "$current_perms" != "600" ]; then
  echo "==> Restricting .env permissions to 600"
  chmod 600 .env
fi

echo "==> Building and starting the prod-profile stack..."
docker compose -f compose.yml --profile prod up -d --build

domain="$(grep -E '^DOMAIN=' .env | tail -1 | cut -d= -f2-)"
echo ""
echo "==> Done! Stack should be live at https://$domain"
echo "    (Let's Encrypt certificate issuance can take up to a minute on first boot; DNS"
echo "    propagation for a freshly-changed DuckDNS record can take a few minutes more.)"
