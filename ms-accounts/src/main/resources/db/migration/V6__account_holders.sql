-- CAM-01: cuentas compartidas con múltiples titulares (máximo 4 por cuenta).
CREATE TABLE account_holders (
    account_id BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, user_id)
);

CREATE INDEX idx_account_holders_user_id ON account_holders (user_id, account_id);

-- Backfill de cuentas existentes: el titular creador pasa a figurar en account_holders.
INSERT INTO account_holders (account_id, user_id, added_at)
SELECT id, owner_id, created_at
FROM accounts
ON CONFLICT (account_id, user_id) DO NOTHING;
