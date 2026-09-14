#!/usr/bin/env bash
set -Eeuo pipefail

readonly health_url="${XUPAN_HEALTHCHECK_URL:-http://127.0.0.1:8080/actuator/health}"
readonly temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/xupan-healthcheck.XXXXXX")"
readonly response_file="${temp_dir}/response.json"

cleanup() {
    rm -rf -- "${temp_dir}"
}
trap cleanup EXIT

timestamp() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
}

log_failure() {
    printf '%s level=ERROR component=xupan-healthcheck %s\n' "$(timestamp)" "$*" >&2
}

set +e
http_status="$(curl --fail --silent --show-error --max-time 5 \
    --output "${response_file}" \
    --write-out '%{http_code}' \
    "${health_url}" 2>/dev/null)"
curl_exit=$?
set -e

if (( curl_exit != 0 )); then
    log_failure "curl_exit=${curl_exit} http_status=${http_status:-unknown} error=transport_failure"
    exit 1
fi

if [[ "${http_status}" != "200" ]]; then
    log_failure "curl_exit=0 http_status=${http_status:-unknown} error=unexpected_http_status"
    exit 1
fi

response_body="$(tr -d '\r\n' < "${response_file}")"
if [[ "${response_body}" != \{*\} ]] || ! grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "${response_file}"; then
    log_failure "curl_exit=0 http_status=200 error=health_status_not_UP"
    exit 1
fi

printf '%s level=INFO component=xupan-healthcheck result=UP http_status=200\n' "$(timestamp)"
