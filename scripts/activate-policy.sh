#!/bin/sh
set -eu
if [ "$#" -ne 1 ]; then echo "Usage: $0 path/to/policy.yaml" >&2; exit 1; fi
policy_directory=$(cd "$(dirname "$1")" && pwd)
policy_file="$policy_directory/$(basename "$1")"
test -f "$policy_file" || { echo 'Policy file does not exist' >&2; exit 1; }
cd "$(dirname "$0")/.."
docker compose run --rm --no-deps -e FRAUD_POLICY_COMMAND=activate \
  -v "$policy_file:/policy/input.yaml:ro" -e FRAUD_POLICY_INPUT=file:/policy/input.yaml policy-seed
