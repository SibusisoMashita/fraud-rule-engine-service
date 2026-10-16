#!/bin/sh
# Scan the complete deployment. Generated evidence stays outside submission history.
set -eu
cd "$(dirname "$0")/.."
if [ "$#" -ne 0 ]; then
    echo 'Usage: security-check.sh' >&2
    exit 2
fi
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
# Exact packaged dependency coverage is checked above; avoid duplicate remote POM resolution.
"$scanner" --quiet fs --offline-scan --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
    --skip-dirs target --skip-dirs .git --skip-dirs .local-notes --skip-dirs secrets --skip-dirs .idea \
    --format json --output target/security/source.json . || result=1
"$scanner" --quiet sbom --scanners vuln --severity HIGH,CRITICAL --exit-code 1 \
    --format json --output target/security/dependencies.json target/security/bom.json || result=1
# Java libraries are scanned by exact packaged inventory above, not fingerprint guesses.
"$scanner" --quiet image --pkg-types os --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
    --format json --output target/security/image.json "$image" || result=1
for service in db redis; do
    reference=$(docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"][sys.argv[1]]["image"])' "$service")
    "$scanner" --quiet image --pkg-types os --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
        --format json --output "target/security/$service.json" "$reference" || result=1
done
# The operational fault sidecar is test-only but must pass the same severity gate.
proxy_image=$(python3 -B -c 'import sys; sys.path.insert(0,"scripts/verification"); from commit_fault import build_image; print(build_image())')
"$scanner" --quiet image --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
    --format json --output target/security/commit-proxy.json "$proxy_image" || result=1
"$scanner" --version >> target/security/scanner.txt
exit "$result"
