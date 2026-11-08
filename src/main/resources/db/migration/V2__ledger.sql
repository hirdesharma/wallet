-- LLD §2.2 / §2.3 — journals and postings

CREATE TABLE journals (
    id                    BIGSERIAL PRIMARY KEY,
    type                  TEXT   NOT NULL CHECK (type IN ('TOP_UP','TRANSFER','WITHDRAW','REFUND','REVERSAL')),
    amount                BIGINT NOT NULL CHECK (amount > 0),
    reference_journal_id  BIGINT REFERENCES journals(id),
    idempotency_key       TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT journals_reference_iff_correction
        CHECK ((type IN ('REFUND','REVERSAL')) = (reference_journal_id IS NOT NULL))
);

CREATE INDEX journals_reference_idx ON journals(reference_journal_id);

-- BR-10: a journal can be reversed at most once
CREATE UNIQUE INDEX journals_one_reversal_idx
    ON journals(reference_journal_id) WHERE type = 'REVERSAL';

CREATE TABLE postings (
    id          BIGSERIAL PRIMARY KEY,
    journal_id  BIGINT NOT NULL REFERENCES journals(id),
    account_id  BIGINT NOT NULL REFERENCES accounts(id),
    amount      BIGINT NOT NULL CHECK (amount <> 0),   -- signed: + debit, − credit
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX postings_account_idx ON postings(account_id, id DESC);
CREATE INDEX postings_journal_idx ON postings(journal_id);
