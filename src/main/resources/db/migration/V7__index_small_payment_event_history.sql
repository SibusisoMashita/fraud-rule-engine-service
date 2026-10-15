-- Event-time count and sum remain independent of public pagination and current instrument.
CREATE INDEX transactions_customer_currency_event_small_payments
    ON fraud.transactions (scope, customer_id, currency, occurred_at)
    INCLUDE (customer_sequence, amount);
UPDATE fraud.service_metadata SET version = 7 WHERE version = 6;
