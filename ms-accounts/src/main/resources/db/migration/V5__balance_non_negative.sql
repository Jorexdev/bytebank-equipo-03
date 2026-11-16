-- CON-01: el saldo nunca puede ser negativo.
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_balance_non_negative CHECK (balance >= 0);