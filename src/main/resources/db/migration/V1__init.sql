CREATE TABLE orders (
    order_id UUID Primary Key,
    customer_id UUID NOT NULL,
    total_amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL
);
CREATE TABLE order_lines (
    order_id UUID,
    line_index INT,
    product_id VARCHAR(100) NOT NULL,
    quantity INT NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    Primary Key(order_id, line_index),
    FOREIGN KEY (order_id) REFERENCES orders (order_id)
);
CREATE TABLE payments (
    payment_id UUID Primary Key,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL,
    order_id UUID NOT NULL
);
CREATE TABLE payment_authorizations (
    authorization_id UUID Primary Key,
    payment_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    psp_reference VARCHAR(100),
    status VARCHAR(30) NOT NULL,
    authorized_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    FOREIGN KEY (payment_id) REFERENCES payments (payment_id)
);
CREATE TABLE payment_psp_dispatch_events (
    dispatch_event_id UUID Primary Key,
    payment_id UUID NOT NULL,
    operation VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempts SMALLINT NOT NULL,
    claimed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX scanning_outbox ON payment_psp_dispatch_events (created_at)
WHERE status IN ('PENDING', 'SENDING');