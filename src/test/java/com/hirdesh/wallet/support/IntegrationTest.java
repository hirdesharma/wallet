package com.hirdesh.wallet.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base class: full app on a random port + real Postgres + a clean database before every test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TransactionTemplate tx;

    @Autowired
    private Environment env;

    protected ApiClient api;

    @BeforeEach
    void cleanDatabase() {
        // TRUNCATE skips row-level triggers, so the append-only guard doesn't block cleanup.
        jdbc.sql("TRUNCATE idempotency_keys, postings, journals, accounts RESTART IDENTITY CASCADE").update();
        jdbc.sql("INSERT INTO accounts (type, code) VALUES ('CASH_POOL', 'CASH_POOL')").update();
        api = new ApiClient(Integer.parseInt(env.getRequiredProperty("local.server.port")));
    }

    protected long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    protected long poolBalance() {
        return jdbc.sql("SELECT balance FROM accounts WHERE code = 'CASH_POOL'").query(Long.class).single();
    }
}
