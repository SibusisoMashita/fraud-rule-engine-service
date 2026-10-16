FROM python:3.13.16-alpine3.24@sha256:2d9aefe2fef018a7eb2c13064c89c71929800fd2e5dccdbf52ea5da5bb8d929a
# The wire-fault harness uses only stdlib; omit unused installers and vendored packages.
RUN rm -rf /usr/local/lib/python3.13/ensurepip /usr/local/lib/python3.13/site-packages/* /usr/local/bin/pip*
USER 65534:65534
