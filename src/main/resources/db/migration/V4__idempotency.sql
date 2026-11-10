-- LLD §2.5 / ADR-004 — idempotency keys (claim → work → complete, with lease + fencing token)

CREATE TABLE idempotency_keys (
    key              TEXT PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 100),
    request_hash     TEXT NOT NULL,
    status           TEXT NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    owner_token      UUID,           -- fencing token of the current owner (while IN_PROGRESS)
    locked_until     TIMESTAMPTZ,    -- lease expiry (while IN_PROGRESS)
    response_status  INT,            -- set when COMPLETED
    response_body    TEXT,           -- exact JSON bytes we returned, replayed verbatim
    journal_id       BIGINT REFERENCES journals(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,

    CONSTRAINT idem_in_progress_has_owner
        CHECK (status <> 'IN_PROGRESS' OR (owner_token IS NOT NULL AND locked_until IS NOT NULL)),
    CONSTRAINT idem_completed_has_response
        CHECK (status <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);
