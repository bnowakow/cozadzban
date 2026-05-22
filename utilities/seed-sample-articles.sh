#!/usr/bin/env bash
# Seed the local API with sample articles for UI/perf testing.
#
# Reads utilities/sample-articles.json and POSTs each entry to
# {BASE_URL}/api/articles using the machine-to-machine API key.
#
# Auth is resolved from process env first, then from <repo-root>/.env:
#   M2M_KEY     <-  APP_MACHINE_AUTH_API_KEY
#                   APP_FACEBOOK
#                   _IMPORT_TARGET_API_KEY
#   M2M_HEADER  <-  APP_MACHINE_AUTH_HEADER_NAME
#                   APP_FACEBOOK_IMPORT_TARGET_API_KEY_HEADER  (fallback: X-CoZaDzban-M2M-Key)
#
# BASE_URL is intentionally NOT read from .env (the FB importer's target may
# point at prod). Defaults to http://localhost:8080.
#
# Safety guards:
#   * Targeting cozadzban.pl (any subdomain) prints a red PRODUCTION warning
#     and requires you to type 'yes' interactively, or set CONFIRM_PRODUCTION=yes.
#   * Targeting any other non-localhost host is refused unless ALLOW_NON_LOCAL=1.
#
# Other optional env:
#   BASE_URL            Default: http://localhost:8080
#   ENV_FILE            Path to .env (default: <repo-root>/.env)
#   INPUT_FILE          Default: utilities/sample-articles.json
#   SLEEP_MS            Pause between requests (default 250). Enrichment hits live URLs.
#   CONFIRM_PRODUCTION  Set to 'yes' to skip the interactive prompt for cozadzban.pl.
#   ALLOW_NON_LOCAL     Set to '1' to allow non-localhost, non-cozadzban hosts without prompting.
#
# Existing URLs return 409 and are reported as "skipped" without aborting.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"

load_env_file() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    local line="${raw#"${raw%%[![:space:]]*}"}"
    [[ -z "$line" || "${line:0:1}" == "#" ]] && continue
    local key="${line%%=*}"
    local value="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ "$key" == *[!A-Za-z0-9_]* || -z "$key" ]]; then continue; fi
    if [[ ( "${value:0:1}" == '"' && "${value: -1}" == '"' ) ||
          ( "${value:0:1}" == "'" && "${value: -1}" == "'" ) ]]; then
      value="${value:1:${#value}-2}"
    fi
    if [[ -z "${!key:-}" ]]; then
      printf -v "$key" '%s' "$value"
      # shellcheck disable=SC2163  # dynamic export of variable named by $key is intentional
      export "$key"
    fi
  done < "$file"
}

load_env_file "$ENV_FILE"

BASE_URL="${BASE_URL:-http://localhost:8080}"

# shellcheck disable=SC2034  # used later in the run banner
key_source=""
if [[ -n "${M2M_KEY:-}" ]]; then
  key_source="M2M_KEY (process env)"
elif [[ -n "${APP_MACHINE_AUTH_API_KEY:-}" ]]; then
  M2M_KEY="$APP_MACHINE_AUTH_API_KEY"
  key_source="APP_MACHINE_AUTH_API_KEY"
elif [[ -n "${APP_FACEBOOK_IMPORT_TARGET_API_KEY:-}" ]]; then
  M2M_KEY="$APP_FACEBOOK_IMPORT_TARGET_API_KEY"
  key_source="APP_FACEBOOK_IMPORT_TARGET_API_KEY"
else
  M2M_KEY=""
fi

M2M_HEADER="${M2M_HEADER:-${APP_MACHINE_AUTH_HEADER_NAME:-${APP_FACEBOOK_IMPORT_TARGET_API_KEY_HEADER:-X-CoZaDzban-M2M-Key}}}"
API_PATH="${API_PATH:-/api/articles}"
INPUT_FILE="${INPUT_FILE:-$SCRIPT_DIR/sample-articles.json}"
SLEEP_MS="${SLEEP_MS:-250}"

if [[ -t 2 ]]; then
  RED=$'\033[1;31m'
  RED_BG=$'\033[1;37;41m'
  RESET=$'\033[0m'
else
  RED="" RED_BG="" RESET=""
fi

if [[ -t 1 ]]; then
  C_GREEN=$'\033[1;32m'
  C_YELLOW=$'\033[1;33m'
  C_RED=$'\033[1;31m'
  C_RESET=$'\033[0m'
else
  C_GREEN="" C_YELLOW="" C_RED="" C_RESET=""
fi

host="$(printf '%s' "$BASE_URL" | sed -E 's#^[a-zA-Z]+://([^/:]+).*#\1#')"
is_localhost=0
is_production=0
case "$host" in
  localhost|127.0.0.1|::1) is_localhost=1 ;;
  cozadzban.pl|*.cozadzban.pl) is_production=1 ;;
esac

if (( is_localhost == 0 )); then
  if (( is_production == 1 )); then
    {
      printf '\n%s ============================================================ %s\n' "$RED_BG" "$RESET"
      printf '%s  WARNING: BASE_URL points at PRODUCTION (%s)                 %s\n' "$RED_BG" "$host" "$RESET"
      printf '%s  This will POST %s sample articles into the live database.     %s\n' "$RED_BG" "$(jq 'length' "${INPUT_FILE}" 2>/dev/null || echo '?')" "$RESET"
      printf '%s ============================================================ %s\n\n' "$RED_BG" "$RESET"
    } >&2

    if [[ "${CONFIRM_PRODUCTION:-}" == "yes" ]]; then
      printf '%sCONFIRM_PRODUCTION=yes set, proceeding without prompt.%s\n\n' "$RED" "$RESET" >&2
    elif [[ -t 0 && -t 2 ]]; then
      printf '%sType %syes%s%s to send to PRODUCTION, anything else to abort:%s ' \
        "$RED" "$RESET$RED_BG" "$RESET" "$RED" "$RESET" >&2
      read -r answer
      if [[ "$answer" != "yes" ]]; then
        printf '%sAborted.%s\n' "$RED" "$RESET" >&2
        exit 2
      fi
    else
      printf '%sERROR: refusing to target PRODUCTION non-interactively. Set CONFIRM_PRODUCTION=yes to bypass.%s\n' "$RED" "$RESET" >&2
      exit 2
    fi
  elif [[ "${ALLOW_NON_LOCAL:-0}" != "1" ]]; then
    printf '%sERROR: BASE_URL points at %s (not localhost). Refusing without ALLOW_NON_LOCAL=1.%s\n' "$RED" "$host" "$RESET" >&2
    printf '       Pass BASE_URL=http://localhost:8080 (or set ALLOW_NON_LOCAL=1 if you really mean it).\n' >&2
    exit 2
  fi
fi

if [[ -z "$M2M_KEY" ]]; then
  echo "ERROR: no API key found. Set M2M_KEY, APP_MACHINE_AUTH_API_KEY, or APP_FACEBOOK_IMPORT_TARGET_API_KEY (checked process env and $ENV_FILE)." >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: this script requires jq." >&2
  exit 2
fi

if [[ ! -f "$INPUT_FILE" ]]; then
  echo "ERROR: input file not found: $INPUT_FILE" >&2
  exit 2
fi

total=$(jq 'length' "$INPUT_FILE")
created=0
skipped=0
failed=0
index=0

echo "Seeding $total articles to ${BASE_URL%/}${API_PATH}"
echo "  header: $M2M_HEADER"
echo "  key:    ${#M2M_KEY} chars from ${key_source:-<unknown>}"
echo

while IFS= read -r payload; do
  index=$((index + 1))
  url=$(jq -r '.url' <<<"$payload")

  http_code=$(curl --silent --show-error --output /tmp/seed-sample-articles.body \
    --write-out '%{http_code}' \
    --request POST "${BASE_URL%/}${API_PATH}" \
    --header "Content-Type: application/json" \
    --header "$M2M_HEADER: $M2M_KEY" \
    --data "$payload" || echo "000")

  case "$http_code" in
    201)
      created=$((created + 1))
      printf '[%3d/%d] %s201 CREATED%s   %s\n' "$index" "$total" "$C_GREEN" "$C_RESET" "$url"
      ;;
    409)
      skipped=$((skipped + 1))
      printf '[%3d/%d] %s409 SKIPPED%s   %s (already imported)\n' "$index" "$total" "$C_YELLOW" "$C_RESET" "$url"
      ;;
    *)
      failed=$((failed + 1))
      body=$(head -c 300 /tmp/seed-sample-articles.body || true)
      printf '[%3d/%d] %s%s FAILED%s    %s\n          %s%s%s\n' "$index" "$total" "$C_RED" "$http_code" "$C_RESET" "$url" "$C_RED" "$body" "$C_RESET"
      if [[ "$http_code" == "401" && "$index" == "1" ]]; then
        printf '\n%sHint: server rejected the M2M key (auth failed). On the server, .env must include:%s\n' "$RED" "$RESET" >&2
        printf '  APP_MACHINE_AUTH_ENABLED=true\n' >&2
        printf '  APP_MACHINE_AUTH_API_KEY=<same value the client sends>\n' >&2
        printf '  APP_MACHINE_AUTH_PRINCIPAL_EMAIL=<existing user email>\n' >&2
        printf '  APP_MACHINE_AUTH_HEADER_NAME=%s   # (optional, this is the default)\n\n' "$M2M_HEADER" >&2
      elif [[ "$http_code" == "403" && "$index" == "1" ]]; then
        printf '\n%sHint: key accepted, but authorization denied. The server-side principal email%s\n' "$RED" "$RESET" >&2
        printf '  APP_MACHINE_AUTH_PRINCIPAL_EMAIL=<email>\n' >&2
        printf '%smust match an existing row in app_user with status=ACTIVE. Check the server log%s\n' "$RED" "$RESET" >&2
        printf '%sfor "Article write authorization decision" to see the email being looked up.%s\n\n' "$RED" "$RESET" >&2
      fi
      ;;
  esac

  if (( SLEEP_MS > 0 )); then
    sleep "$(awk "BEGIN { printf \"%.3f\", ${SLEEP_MS}/1000 }")"
  fi
done < <(jq -c '.[]' "$INPUT_FILE")

rm -f /tmp/seed-sample-articles.body
echo
echo "Done: $created created, $skipped skipped, $failed failed (of $total)."
exit $(( failed == 0 ? 0 : 1 ))
