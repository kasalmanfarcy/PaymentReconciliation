# shellcheck shell=bash
# Log in to Keycloak from a script the way a native app does: authorization code with PKCE and a
# loopback redirect URI (RFC 8252, RFC 7636), through the realm's public `reconflow-cli` client.
# The password grant is the local realm's convenience only; a deployed realm doesn't offer it
# (docs/security.md S-67), so scripts that must also work against a deployment use this.
#
#   source scripts/lib/oidc.sh
#   TOKEN=$(oidc_login "$KEYCLOAK_URL" "$USERNAME" "$PASSWORD")
#
# What it does, in plain HTTP: GET the authorization endpoint with a code challenge (Keycloak answers
# with its login form and a session cookie), POST the credentials to the form's action, read the
# authorization code from the redirect (nothing listens on the loopback port; the code is taken
# from the Location header), then exchange code + verifier for tokens. Needs curl, jq, openssl.
# CURL_OPTS (an array) is passed to every call, e.g. (--cacert ca.pem) for a private CA.

OIDC_REALM=${OIDC_REALM:-reconflow}
OIDC_CLIENT_ID=${OIDC_CLIENT_ID:-reconflow-cli}
OIDC_REDIRECT_URI=${OIDC_REDIRECT_URI:-http://127.0.0.1:8765/callback}

_oidc_b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

oidc_login() {
  local kc=$1 username=$2 password=$3
  local jar verifier challenge state auth_url page action location code
  jar=$(mktemp)
  # shellcheck disable=SC2064
  trap "rm -f '$jar'" RETURN
  verifier=$(openssl rand 48 | _oidc_b64url)
  challenge=$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | _oidc_b64url)
  state=$(openssl rand 16 | _oidc_b64url)

  auth_url="$kc/realms/$OIDC_REALM/protocol/openid-connect/auth?response_type=code&scope=openid"
  auth_url+="&client_id=$OIDC_CLIENT_ID&redirect_uri=$(jq -rn --arg u "$OIDC_REDIRECT_URI" '$u|@uri')"
  auth_url+="&state=$state&code_challenge=$challenge&code_challenge_method=S256"
  page=$(curl -fsS ${CURL_OPTS[@]+"${CURL_OPTS[@]}"} -c "$jar" -b "$jar" "$auth_url") || {
    echo "oidc_login: the authorization endpoint at $kc did not answer" >&2; return 1; }

  # The login form's action URL, with HTML entities undone.
  action=$(printf '%s' "$page" | grep -o 'id="kc-form-login"[^>]*action="[^"]*"\|action="[^"]*"[^>]*id="kc-form-login"' \
    | grep -o 'action="[^"]*"' | head -1 | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')
  [[ -n "$action" ]] || { echo "oidc_login: no login form in Keycloak's answer" >&2; return 1; }

  location=$(curl -sS ${CURL_OPTS[@]+"${CURL_OPTS[@]}"} -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' \
    --data-urlencode "username=$username" --data-urlencode "password=$password" \
    --data-urlencode "credentialId=" "$action")
  case "$location" in
    "$OIDC_REDIRECT_URI"*) ;;
    *) echo "oidc_login: login refused for $username (wrong password, locked, or required action)" >&2; return 1 ;;
  esac
  [[ "$location" == *"state=$state"* ]] || { echo "oidc_login: state mismatch" >&2; return 1; }
  code=$(printf '%s' "$location" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')

  curl -fsS ${CURL_OPTS[@]+"${CURL_OPTS[@]}"} "$kc/realms/$OIDC_REALM/protocol/openid-connect/token" \
    -d grant_type=authorization_code -d "client_id=$OIDC_CLIENT_ID" \
    --data-urlencode "code=$code" --data-urlencode "redirect_uri=$OIDC_REDIRECT_URI" \
    --data-urlencode "code_verifier=$verifier" | jq -er .access_token
}
