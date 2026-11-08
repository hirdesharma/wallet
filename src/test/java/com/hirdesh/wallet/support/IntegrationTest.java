package com.hirdesh.wallet.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Base class: Spring context + real Postgres + a clean database before every test. */
@SpringBootTest
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TransactionTemplate tx;

    @BeforeEach
    void cleanDatabase() {
        // TRUNCATE skips row-level triggers, so the append-only guard doesn't block cleanup.
        jdbc.sql("TRUNCATE postings, journals, accounts RESTART IDENTITY CASCADE").update();
        jdbc.sql("INSERT INTO accounts (type, code) VALUES ('CASH_POOL', 'CASH_POOL')").update();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
