-- CAM-02: consulta de pendientes y auditoría de la revisión por gestor.
CREATE INDEX idx_transfers_status_created_at ON transfers (status, created_at DESC);

ALTER TABLE transfers
    ADD COLUMN reviewed_by BIGINT,
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD COLUMN review_note VARCHAR(255);