ALTER TABLE payment_psp_dispatch_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- 既存行を埋めるためだけの DEFAULT。以降は挿入・更新のたびに明示的に値を入れる。
ALTER TABLE payment_psp_dispatch_events
    ALTER COLUMN next_attempt_at DROP DEFAULT;
