#!/usr/bin/env bash
set -euo pipefail

: "${WEBHOOK_URL:?set WEBHOOK_URL, e.g. https://<public-host>/v1/webhooks/momo}"
: "${SECRET_KEY:?set SECRET_KEY to the Paystack secret key}"
: "${REFERENCE:?set REFERENCE to the contribution/provider reference}"
: "${AMOUNT_PESEWAS:?set AMOUNT_PESEWAS to the total charged}"
OUTCOME="${OUTCOME:-success}"

if [[ "$OUTCOME" != "success" && "$OUTCOME" != "failed" ]]; then
  echo "OUTCOME must be success or failed" >&2
  exit 2
fi

PAID_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
BODY="$(printf '{"event":"charge.%s","data":{"reference":"%s","status":"%s","amount":%s,"currency":"GHS","paid_at":"%s"}}' \
  "$OUTCOME" "$REFERENCE" "$OUTCOME" "$AMOUNT_PESEWAS" "$PAID_AT")"
SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha512 -hmac "$SECRET_KEY" | awk '{print $2}')"

curl -sS -o /dev/null -w '%{http_code}\n' \
  -X POST "$WEBHOOK_URL" \
  -H 'content-type: application/json' \
  -H "x-paystack-signature: $SIGNATURE" \
  --data "$BODY"
