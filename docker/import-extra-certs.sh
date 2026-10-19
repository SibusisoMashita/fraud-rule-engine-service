#!/bin/sh
# Add optional organisation certificates to this build JDK only.
set -eu

if [ "$#" -ne 1 ]; then
    echo 'Usage: import-extra-certs.sh directory' >&2
    exit 2
fi

certificate_directory=$1
certificate_work=$(mktemp -d)
trap 'rm -rf "$certificate_work"' 0
trap 'exit 1' HUP INT TERM
imported=0

for bundle in "$certificate_directory"/*.crt "$certificate_directory"/*.pem; do
    [ -f "$bundle" ] || continue
    # keytool imports only the first certificate from a bundle, so split it first.
    if ! awk -v directory="$certificate_work" '
        /-----BEGIN CERTIFICATE-----/ {
            if (certificate != "") exit 1
            certificate = directory "/certificate-" ++count ".pem"
        }
        certificate != "" { print > certificate }
        /-----END CERTIFICATE-----/ {
            if (certificate == "") exit 1
            close(certificate)
            certificate = ""
        }
        END { if (certificate != "" || count == 0) exit 1 }
    ' "$bundle"; then
        printf 'Could not read PEM certificates from %s\n' "$bundle" >&2
        exit 1
    fi

    for certificate in "$certificate_work"/certificate-*.pem; do
        [ -f "$certificate" ] || continue
        imported=$((imported + 1))
        keytool -importcert -noprompt -trustcacerts -cacerts -storepass changeit \
            -alias "extra-ca-$imported" -file "$certificate"
        rm -f "$certificate"
    done
done

printf 'Imported %s extra CA certificate(s).\n' "$imported"
