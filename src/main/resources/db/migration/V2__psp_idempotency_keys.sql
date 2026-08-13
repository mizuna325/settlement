CREATE TABLE payment_psp_idempotency_keys (
    dispatch_event_id UUID Primary Key,
    created_at TIMESTAMPTZ NOT NULL
);
