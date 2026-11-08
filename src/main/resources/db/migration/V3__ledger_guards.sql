-- LLD §2.4 / ADR-005 — rules the database enforces even if Java code is wrong.

-- (a) Append-only: journals and postings can never be updated or deleted (BR-11).
--     Note: TRUNCATE does not fire row-level triggers (used for test cleanup only).
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER journals_append_only BEFORE UPDATE OR DELETE ON journals
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER postings_append_only BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- (b) Balanced journal: SUM(postings) = 0 and at least 2 postings.
--     DEFERRED = checked at COMMIT, because while postings are being inserted
--     one by one the journal is legitimately unbalanced.
CREATE FUNCTION check_journal_balanced(jid BIGINT) RETURNS void AS $$
DECLARE s BIGINT; c INT;
BEGIN
    SELECT COALESCE(SUM(amount), 0), COUNT(*) INTO s, c FROM postings WHERE journal_id = jid;
    IF s <> 0 OR c < 2 THEN
        RAISE EXCEPTION 'journal % unbalanced (sum=%, postings=%)', jid, s, c;
    END IF;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION trg_journal_balanced() RETURNS trigger AS $$
BEGIN
    IF TG_TABLE_NAME = 'journals' THEN
        PERFORM check_journal_balanced(NEW.id);
    ELSE
        PERFORM check_journal_balanced(NEW.journal_id);
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

-- catches a journal with zero postings
CREATE CONSTRAINT TRIGGER journals_balanced AFTER INSERT ON journals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_journal_balanced();

-- catches postings that unbalance a journal (including ones added later to an old journal)
CREATE CONSTRAINT TRIGGER postings_balanced AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_journal_balanced();
