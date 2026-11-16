-- BUG-05: los importes pasan de DOUBLE PRECISION a NUMERIC(19,2) (dinero exacto, 2 decimales).
-- Los valores existentes se redondean a 2 decimales (limpia los errores tipo 0.30000000000000004).
ALTER TABLE accounts ALTER COLUMN balance DROP DEFAULT;
ALTER TABLE accounts ALTER COLUMN balance TYPE NUMERIC(19, 2) USING ROUND(balance::numeric, 2);
ALTER TABLE accounts ALTER COLUMN balance SET DEFAULT 0;

ALTER TABLE movements ALTER COLUMN amount TYPE NUMERIC(19, 2) USING ROUND(amount::numeric, 2);
ALTER TABLE movements ALTER COLUMN balance_after TYPE NUMERIC(19, 2) USING ROUND(balance_after::numeric, 2);
