package com.hirdesh.wallet.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Insert-only access to journals and postings (they are append-only, ADR-005). */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insertJournal(JournalDraft draft) {
        return jdbc.sql("""
                        INSERT INTO journals (type, amount, reference_journal_id, idempotency_key)
                        VALUES (:type, :amount, :ref, :key)
                        RETURNING id""")
                .param("type", draft.type().name())
                .param("amount", draft.amount())
                .param("ref", draft.referenceJournalId())
                .param("key", draft.idempotencyKey())
                .query(Long.class)
                .single();
    }

    public void insertPosting(long journalId, PostingDraft posting) {
        jdbc.sql("INSERT INTO postings (journal_id, account_id, amount) VALUES (:j, :a, :amt)")
                .param("j", journalId)
                .param("a", posting.accountId())
                .param("amt", posting.amount())
                .update();
    }

    public Optional<Journal> findJournal(long id) {
        return loadJournal(id, "");
    }

    /**
     * Row-locks the journal until the transaction ends (LLD §6.1). Used to serialize all
     * refunds/reversals of the SAME original. Allowed despite the append-only trigger:
     * FOR UPDATE takes a lock but doesn't UPDATE, so BEFORE UPDATE triggers don't fire.
     */
    public Optional<Journal> lockJournal(long id) {
        return loadJournal(id, " FOR UPDATE");
    }

    /** Total already refunded against a transfer. Call while holding the journal lock. */
    public long refundedAmount(long originalJournalId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM journals
                        WHERE reference_journal_id = :id AND type = 'REFUND'""")
                .param("id", originalJournalId)
                .query(Long.class)
                .single();
    }

    public boolean isReversed(long originalJournalId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM journals
                                       WHERE reference_journal_id = :id AND type = 'REVERSAL')""")
                .param("id", originalJournalId)
                .query(Boolean.class)
                .single();
    }

    private Optional<Journal> loadJournal(long id, String lockClause) {
        // Journal row first, so when locking we take the lock before reading the postings.
        Optional<Journal> header = jdbc.sql("""
                        SELECT id, type, amount, reference_journal_id, idempotency_key, created_at
                        FROM journals WHERE id = :id""" + lockClause)
                .param("id", id)
                .query((rs, i) -> mapJournal(rs, List.of()))
                .optional();

        return header.map(j -> {
            List<Posting> postings = jdbc.sql("""
                            SELECT id, journal_id, account_id, amount
                            FROM postings WHERE journal_id = :id ORDER BY id""")
                    .param("id", id)
                    .query((rs, i) -> new Posting(
                            rs.getLong("id"), rs.getLong("journal_id"),
                            rs.getLong("account_id"), rs.getLong("amount")))
                    .list();
            return new Journal(j.id(), j.type(), j.amount(), j.referenceJournalId(), j.idempotencyKey(),
                    j.createdAt(), postings);
        });
    }

    private static Journal mapJournal(ResultSet rs, List<Posting> postings) throws SQLException {
        return new Journal(
                rs.getLong("id"),
                JournalType.valueOf(rs.getString("type")),
                rs.getLong("amount"),
                rs.getObject("reference_journal_id", Long.class),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                postings);
    }
}
