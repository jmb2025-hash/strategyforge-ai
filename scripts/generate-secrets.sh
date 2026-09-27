#!/usr/bin/env bash
# Prints freshly generated secrets for .env. Nothing is written to disk.
set -euo pipefail
echo "DATABASE_PASSWORD=$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)"
echo "MASTER_ENCRYPTION_KEY=$(openssl rand -base64 32)"
echo "JWT_SIGNING_KEY=$(openssl rand -base64 48)"
echo "SF_BOOTSTRAP_TOKEN=$(openssl rand -hex 16)"
