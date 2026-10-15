CREATE TABLE fraud.service_metadata (
    version integer PRIMARY KEY CHECK (version > 0)
);
INSERT INTO fraud.service_metadata (version) VALUES (1);
GRANT SELECT ON fraud.service_metadata TO fraud_runtime;
