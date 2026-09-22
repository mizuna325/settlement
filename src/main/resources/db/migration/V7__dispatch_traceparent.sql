-- 可観測性(design.md §8.1)。
-- Relay は @Scheduled の別スレッドで、行を積んだ時刻より後に走る。スレッドローカルの
-- コンテキストは越えないため、伝搬したいIDを行そのものに載せる。
-- W3C Trace Context の version 00 は "00-<32桁>-<16桁>-<2桁>" で 55 文字。
ALTER TABLE payment_psp_dispatch_events ADD COLUMN traceparent VARCHAR(55);
