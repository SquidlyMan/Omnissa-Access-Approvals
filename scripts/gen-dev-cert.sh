#!/usr/bin/env bash
# gen-dev-cert.sh — Generate a self-signed certificate for local development.
# NOT for production use.
#
# Usage:  ./scripts/gen-dev-cert.sh [hostname]
# Default hostname: localhost

set -euo pipefail

HOST=${1:-localhost}
OUTPUT="config/keystore.p12"
ALIAS="omnissa-approval"
PASSWORD="${SSL_KEYSTORE_PASSWORD:-changeit}"

mkdir -p config

keytool -genkeypair \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 365 \
    -storetype PKCS12 \
    -keystore "$OUTPUT" \
    -storepass "$PASSWORD" \
    -dname "CN=$HOST, OU=Approval Tool, O=Omnissa, L=Local, ST=Dev, C=US" \
    -ext "SAN=dns:$HOST,dns:localhost,ip:127.0.0.1"

echo "Self-signed cert written to $OUTPUT (valid 365 days, host=$HOST)"
echo "Set SSL_KEYSTORE_PASSWORD=$PASSWORD in your .env file"
echo ""
echo "Add to your browser's trust store or accept the warning for dev use only."
