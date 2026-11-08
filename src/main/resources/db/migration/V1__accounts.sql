-- LLD §2.1 — accounts
-- balance is the RAW signed sum of postings (debit +, credit −). See ADR-001.
-- A user wallet holding ₹300 has balance = -30000.

CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,
    type        TEXT   NOT NULL CHECK (type IN ('USER_WALLET', 'CASH_POOL')),
    user_id     TEXT   UNIQUE,
    code        TEXT   UNIQUE,
    balance     BIGINT NOT NULL DEFAULT 0,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT accounts_user_id_iff_wallet CHECK ((type = 'USER_WALLET') = (user_id IS NOT NULL)),
    CONSTRAINT accounts_wallet_no_overdraft CHECK (type <> 'USER_WALLET' OR balance <= 0),
    CONSTRAINT accounts_pool_non_negative  CHECK (type <> 'CASH_POOL'   OR balance >= 0)
);

-- LLD §2.7 — system accounts
INSERT INTO accounts (type, code) VALUES ('CASH_POOL', 'CASH_POOL');
