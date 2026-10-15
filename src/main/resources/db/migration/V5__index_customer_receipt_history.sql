-- Receipt windows and customer watermark are independent of API pagination.
CREATE INDEX transactions_customer_receipt_count
    ON fraud.transactions (scope, customer_id, accepted_at) INCLUDE (customer_sequence);
CREATE INDEX transactions_customer_currency_receipt_spend
    ON fraud.transactions (scope, customer_id, currency, accepted_at) INCLUDE (customer_sequence, amount);
UPDATE fraud.service_metadata SET version = 5 WHERE version = 4;
