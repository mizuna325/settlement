CREATE TABLE payment_refunds (
    refund_id UUID Primary Key,
    payment_id UUID NOT NULL,
    refund_index INT NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    psp_reference VARCHAR(100),
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (payment_id) REFERENCES payments (payment_id)
);
