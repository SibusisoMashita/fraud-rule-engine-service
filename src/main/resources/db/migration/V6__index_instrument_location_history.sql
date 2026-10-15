-- Event-time neighbors include earlier, equal and later timestamps from delayed delivery.
CREATE INDEX transactions_instrument_location_time
    ON fraud.transactions (scope, instrument_id, occurred_at, event_id)
    INCLUDE (customer_id, customer_sequence, location_source, channel, accuracy_km)
    WHERE location_source IS NOT NULL;
UPDATE fraud.service_metadata SET version = 6 WHERE version = 5;
