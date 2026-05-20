#!/usr/bin/env bash
# import-cert.sh — Convert a PEM certificate + key into a PKCS12 keystore
# that Spring Boot can load directly.
#
# Usage:
#   ./scripts/import-cert.sh cert.pem key.pem [ca-chain.pem]
#
# Output: config/keystore.p12
# The password is taken from SSL_KEYSTORE_PASSWORD env var (default: changeit)

set -euo pipefail

CERT=${1:?"Usage: $0 <cert.pem> <key.pem> [ca-chain.pem]"}
KEY=${2:?"Usage: $0 <cert.pem> <key.pem> [ca-chain.pem]"}
CA=${3:-""}
OUTPUT="config/keystore.p12"
ALIAS="omnissa-approval"
PASSWORD="${SSL_KEYSTORE_PASSWORD:-changeit}"

mkdir -p config

if [[ -n "$CA" ]]; then
    FULLCHAIN=$(mktemp)
    cat "$CERT" "$CA" > "$FULLCHAIN"
    CERT_ARG="$FULLCHAIN"
else
    CERT_ARG="$CERT"
fi

openssl pkcs12 \
    -export \
    -in  "$CERT_ARG" \
    -inkey "$KEY" \
    -out "$OUTPUT" \
    -name "$ALIAS" \
    -passout "pass:$PASSWORD"

[[ -n "$CA" ]] && rm -f "$FULLCHAIN"

echo "Keystore written to $OUTPUT"
echo "Set SSL_KEYSTORE_PASSWORD=$PASSWORD in your .env file"
