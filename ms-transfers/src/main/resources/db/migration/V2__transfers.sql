ALTER TABLE transfers
    ADD COLUMN review_reason VARCHAR(255),
    ADD COLUMN fraud_verdict VARCHAR(20),
    ADD COLUMN fraud_check_id BIGINT;