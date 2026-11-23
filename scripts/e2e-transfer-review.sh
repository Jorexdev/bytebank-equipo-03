#!/usr/bin/env bash

set -Eeuo pipefail

API_BASE="${API_BASE:-http://localhost:8080}"
CLIENT_EMAIL="${CLIENT_EMAIL:-ana@bytebank.test}"
CLIENT_PASSWORD="${CLIENT_PASSWORD:-Cliente1234!}"
GESTOR_EMAIL="${GESTOR_EMAIL:-gestor@bytebank.test}"
GESTOR_PASSWORD="${GESTOR_PASSWORD:-Gestor1234!}"

fail() {
    echo "E2E FAILED: $*" >&2
    exit 1
}

request() {
    local expected="$1"
    shift
    local response status body

    response="$(curl -sS "$@" -w $'\n%{http_code}')" ||
        fail "No se pudo conectar con ${API_BASE}"
    status="${response##*$'\n'}"
    body="${response%$'\n'*}"
    [[ "$status" == "$expected" ]] ||
        fail "HTTP esperado ${expected}, recibido ${status}: ${body}"
    printf '%s' "$body"
}

json_value() {
    local expression="$1"
    python3 -c "import json, sys; print(${expression})"
}

echo "Comprobando login del cliente..."
client_login="$(request 200 \
    -X POST "${API_BASE}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${CLIENT_EMAIL}\",\"password\":\"${CLIENT_PASSWORD}\"}")"
client_token="$(printf '%s' "$client_login" | json_value "json.load(sys.stdin)['token']")"

echo "Comprobando login del gestor..."
gestor_login="$(request 200 \
    -X POST "${API_BASE}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${GESTOR_EMAIL}\",\"password\":\"${GESTOR_PASSWORD}\"}")"
gestor_token="$(printf '%s' "$gestor_login" | json_value "json.load(sys.stdin)['token']")"

echo "Obteniendo una cuenta del cliente..."
accounts="$(request 200 \
    -H "Authorization: Bearer ${client_token}" \
    "${API_BASE}/api/accounts")"
origin_iban="$(printf '%s' "$accounts" | json_value "json.load(sys.stdin)[0]['iban']")"
concept="E2E revision $(date +%s)"

echo "Creando transferencia superior a 5.000 €..."
created="$(request 201 \
    -X POST "${API_BASE}/api/transfers" \
    -H "Authorization: Bearer ${client_token}" \
    -H 'Content-Type: application/json' \
    -d "{\"originIban\":\"${origin_iban}\",\"destinationIban\":\"ES9121000418450200051332\",\"amount\":6000.00,\"concept\":\"${concept}\"}")"
created_status="$(printf '%s' "$created" | json_value "json.load(sys.stdin)['status']")"
[[ "$created_status" == "PENDIENTE_REVISION" ]] ||
    fail "La transferencia nueva quedó en ${created_status}"

echo "Consultando la bandeja del gestor..."
pending="$(request 200 \
    -H "Authorization: Bearer ${gestor_token}" \
    "${API_BASE}/api/transfers/pending?page=0&size=100")"
transfer_id="$(printf '%s' "$pending" | python3 -c '
import json, sys
concept = sys.argv[1]
items = json.load(sys.stdin)["content"]
matches = [item for item in items if item.get("concept") == concept]
if not matches:
    raise SystemExit("No se encontró la transferencia E2E en la bandeja")
item = matches[-1]
if item["status"] != "PENDIENTE_REVISION":
    raise SystemExit("Estado inesperado: " + str(item["status"]))
print(item["id"])
' "$concept")"

echo "Rechazando la transferencia ${transfer_id}..."
rejected="$(request 200 \
    -X POST "${API_BASE}/api/transfers/${transfer_id}/reject" \
    -H "Authorization: Bearer ${gestor_token}" \
    -H 'Content-Type: application/json' \
    -d '{"comment":"Revisión E2E"}')"
printf '%s' "$rejected" | python3 -c '
import json, sys
item = json.load(sys.stdin)
assert item["status"] == "RECHAZADA", item
assert item["rejectionReason"] == "RECHAZADA_POR_GESTOR", item
assert item["reviewNote"] == "Revisión E2E", item
assert item["reviewedBy"] is not None, item
assert item["reviewedAt"] is not None, item
'

echo "E2E OK: transferencia ${transfer_id} creada pendiente y rechazada por un gestor."
