#!/usr/bin/env bash
# A throwaway certificate authority and one certificate for the LOCAL REHEARSAL of the deployment
# (deploy/compose.local.yaml): api.reconflow.localhost and auth.reconflow.localhost. A real deploy
# gets its certificates from Let's Encrypt instead (deploy/traefik/traefik.yml).
#
#   scripts/local_tls.sh        -> deploy/local/certs/{ca.pem,tls.pem,tls-key.pem} (gitignored)
#
# Clients trust it per command (curl --cacert deploy/local/certs/ca.pem), never system-wide. The CA's
# key is deleted once the certificate is signed, so nothing else can ever be issued from it.
set -euo pipefail
cd "$(dirname "$0")/.."
out=deploy/local/certs
if [[ -f "$out/tls.pem" ]]; then echo "$out already exists"; exit 0; fi
mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=ReconFlow rehearsal CA" \
  -keyout "$work/ca-key.pem" -out "$out/ca.pem" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=reconflow.localhost" \
  -keyout "$out/tls-key.pem" -out "$work/tls.csr" 2>/dev/null
printf 'subjectAltName=DNS:api.reconflow.localhost,DNS:auth.reconflow.localhost\nextendedKeyUsage=serverAuth\n' > "$work/ext"
openssl x509 -req -in "$work/tls.csr" -CA "$out/ca.pem" -CAkey "$work/ca-key.pem" -CAcreateserial \
  -days 30 -extfile "$work/ext" -out "$out/tls.pem" 2>/dev/null
chmod 644 "$out"/*.pem   # Traefik runs as another user inside its container
echo "Wrote $out (valid 30 days). Trust it per command: curl --cacert $out/ca.pem"
