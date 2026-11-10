package com.hirdesh.wallet.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, type, user_id, code, balance, version";

    private static final RowMapper<Account> MAPPER = (ResultSet rs, int i) -> mapRow(rs);

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account createUserWallet(String userId) {
        return jdbc.sql("INSERT INTO accounts (type, user_id) VALUES ('USER_WALLET', :userId) RETURNING " + COLUMNS)
                .param("userId", userId)
                .query(MAPPER)
                .single();
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public Account getByCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE code = :code")
                .param("code", code)
                .query(MAPPER)
                .single();
    }

    /**
     * Row lock held until the surrounding transaction ends. While we hold it, no other
     * transaction can lock or update this account, so check-then-act on the balance is safe.
     */
    public Optional<Account> lockById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** Relative update (balance + delta), never an absolute overwrite. */
    public void addToBalance(long id, long delta) {
        jdbc.sql("UPDATE accounts SET balance = balance + :delta WHERE id = :id")
                .param("delta", delta)
                .param("id", id)
                .update();
    }

    /**
     * Optimistic write: succeeds only if nobody changed the row since we read {@code expectedVersion}.
     * If another transaction holds the row, Postgres waits for it, then re-checks the WHERE
     * against the committed row — so a stale version always matches 0 rows.
     */
    public boolean addToBalanceIfVersion(long id, long delta, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE accounts SET balance = balance + :delta, version = version + 1
                        WHERE id = :id AND version = :version""")
                .param("delta", delta)
                .param("id", id)
                .param("version", expectedVersion)
                .update() == 1;
    }

    private static Account mapRow(ResultSet rs) throws SQLException {
        return new Account(
                rs.getLong("id"),
                AccountType.valueOf(rs.getString("type")),
                rs.getString("user_id"),
                rs.getString("code"),
                rs.getLong("balance"),
                rs.getLong("version"));
    }
}
