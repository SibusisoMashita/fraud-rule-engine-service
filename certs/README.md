# Optional organisation CA certificates

If Maven fails during the Docker build with `SSLHandshakeException` or `PKIX path building failed`, your network may inspect HTTPS using an organisation certificate that the build JDK does not trust.

Get the approved root/intermediate CA certificates from your IT team or certificate store. Put their PEM exports in this directory as `*.crt` or `*.pem`; bundles containing several certificates are supported. Include certificates only, without private keys.

Then rebuild from the repository root:

```sh
docker compose build --no-cache app
```

Certificate files are git-ignored. They are imported into the build-stage Java truststore before Maven runs; neither the certificates nor that truststore are copied into the runtime image. With no certificate files present, the script reports `Imported 0 extra CA certificate(s).` and uses the JDK's normal trust settings.

This configures Java build trust. If Docker cannot pull the base image, ask IT about Docker's own certificate/proxy configuration.

See [Corporate networks (TLS inspection)](../README.md#corporate-networks-tls-inspection) for detection and a macOS export example.
