#!/bin/sh
# Scanner/database evidence is generated output; findings are never silently ignored.
set -eu
cd "$(dirname "$0")/.."
include_services=false
case "${1:-}" in
    '') ;;
    --include-services) include_services=true;;
    *) echo 'Usage: security-check.sh [--include-services]' >&2; exit 2;;
esac
scanner=${TRIVY_BIN:-trivy}
version=0.76.0
"$scanner" --version | head -n 1 | grep -qx "Version: $version" || {
    echo "Trivy $version is required; see docs/OPERATIONS.md" >&2; exit 1;
}
mkdir -p target/security
image=$(docker image inspect --format '{{.Id}}' fraud-rule-engine-service:local)
printf '%s\n' "$image" > target/security/image-id.txt
container=$(docker create "$image")
inventory=$(mktemp -d target/security/inventory.XXXXXX)
cleanup() {
    docker rm "$container" >/dev/null
    rm -rf "$inventory"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker cp "$container:/app/bom.json" target/security/bom.json
docker cp "$container:/app/service.jar" "$inventory/service.jar"
python3 -B scripts/verification/inventory.py target/security/bom.json "$inventory/service.jar" > target/security/inventory.json
"$scanner" --version > target/security/scanner.txt
result=0
"$scanner" --quiet fs --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
    --skip-dirs target --skip-dirs .git --skip-dirs .local-notes --skip-dirs secrets --skip-dirs .idea \
    --format json --output target/security/source.json . || result=1
"$scanner" --quiet sbom --scanners vuln --severity HIGH,CRITICAL --exit-code 1 \
    --format json --output target/security/dependencies.json target/security/bom.json || result=1
# Java libraries are scanned by exact packaged inventory above, not fingerprint guesses.
"$scanner" --quiet image --pkg-types os --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
    --format json --output target/security/image.json "$image" || result=1
if [ "$include_services" = true ]; then
    for service in db redis; do
        reference=$(docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"][sys.argv[1]]["image"])' "$service")
        "$scanner" --quiet image --pkg-types os --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
            --format json --output "target/security/$service.json" "$reference" || result=1
    done
fi
"$scanner" --version >> target/security/scanner.txt
exit "$result"
