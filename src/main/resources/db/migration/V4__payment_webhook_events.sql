CREATE TABLE payment_webhook_events(
    event_id VARCHAR(100) Primary key,
    received_at TIMESTAMPTZ NOT NULL
)