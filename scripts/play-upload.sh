#!/bin/sh
# Sends a signed AAB to one of Play's testing tracks (stratus-app#92, #204),
# with the Play Developer API and nothing but curl, openssl and jq: a
# third-party action here would be code nobody reviewed holding a key that can
# publish the app.
#
#   PLAY_SERVICE_ACCOUNT_JSON='<key>' scripts/play-upload.sh app.aab 0.1.7 [internal|closed|open]
set -eu

AAB="$1"
NAME="$2"
PACKAGE=dev.stratus.app
# The Console's names on the left, the API's on the right, which still calls
# closed and open testing by what they were called before.
case "${3:-internal}" in
  internal) TRACK=internal ;;
  closed) TRACK=alpha ;;
  open) TRACK=beta ;;
  *) echo "no such track: $3 (internal, closed or open)" >&2; exit 2 ;;
esac
API="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PACKAGE"
UPLOAD="https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$PACKAGE"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
umask 077

printf %s "$PLAY_SERVICE_ACCOUNT_JSON" > "$WORK/account.json"
jq -r .private_key "$WORK/account.json" > "$WORK/key.pem"
EMAIL="$(jq -r .client_email "$WORK/account.json")"
TOKEN_URI="$(jq -r .token_uri "$WORK/account.json")"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now="$(date +%s)"
header="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
claims="$(jq -cn --arg iss "$EMAIL" --arg aud "$TOKEN_URI" --argjson iat "$now" \
  '{iss: $iss, aud: $aud, iat: $iat, exp: ($iat + 600),
    scope: "https://www.googleapis.com/auth/androidpublisher"}' | b64url)"
signature="$(printf %s "$header.$claims" | openssl dgst -sha256 -sign "$WORK/key.pem" | b64url)"

# Every answer goes through a file, so a refusal is printed rather than piped
# into jq and lost.
fetch() {
  curl -sS --fail-with-body -o "$WORK/out" "$@" || { cat "$WORK/out" >&2; echo >&2; return 1; }
}

fetch "$TOKEN_URI" \
  --data-urlencode grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer \
  --data-urlencode "assertion=$header.$claims.$signature"
printf 'Authorization: Bearer %s\n' "$(jq -r .access_token "$WORK/out")" > "$WORK/auth"

call() { fetch -H @"$WORK/auth" "$@"; }

call -X POST "$API/edits"
edit="$(jq -r .id "$WORK/out")"
call -X POST -H 'Content-Type: application/octet-stream' \
  --data-binary @"$AAB" "$UPLOAD/edits/$edit/bundles?uploadType=media"
code="$(jq -r .versionCode "$WORK/out")"
jq -cn --arg track "$TRACK" --arg code "$code" --arg name "$NAME" \
  '{track: $track, releases: [{name: $name, versionCodes: [$code], status: "completed"}]}' > "$WORK/track.json"
call -X PUT -H 'Content-Type: application/json' --data @"$WORK/track.json" "$API/edits/$edit/tracks/$TRACK"
call -X POST "$API/edits/$edit:commit"

echo "versionCode $code is on the $TRACK track as $NAME"
