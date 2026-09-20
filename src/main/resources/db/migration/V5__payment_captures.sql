CREATE TABLE payment_captures (
    capture_id UUID Primary Key,
    payment_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    psp_reference VARCHAR(100),
    status VARCHAR(30) NOT NULL,
    captured_at TIMESTAMPTZ,
    FOREIGN KEY (payment_id) REFERENCES payments (payment_id)
);
