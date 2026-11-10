package com.hirdesh.wallet.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hirdesh.wallet.support.ApiClient.Response;
import com.hirdesh.wallet.support.IntegrationTest;

/** S-10 .. S-15 through the real HTTP API. Amounts are in paise. */
class MoneyMovementApiTest extends IntegrationTest {

    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        alice = api.createWallet("alice");
        bob = api.createWallet("bob");
    }

    @Test
    void s10_topUp() {
        Response r = api.topUp(alice, 50_000);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.str("$.type")).isEqualTo("TOP_UP");
        assertThat(api.balance(alice)).isEqualTo(50_000);
        assertThat(poolBalance()).isEqualTo(50_000);
    }

    @Test
    void s11_transfer() {
        api.topUp(alice, 50_000);

        Response r = api.transfer(alice, bob, 20_000);

        assertThat(r.status()).isEqualTo(201);
        assertThat(api.balance(alice)).isEqualTo(30_000);
        assertThat(api.balance(bob)).isEqualTo(20_000);
        assertThat(poolBalance()).isEqualTo(50_000); // P2P never touches the pool
    }

    @Test
    void s12_withdraw() {
        api.topUp(alice, 50_000);

        Response r = api.withdraw(alice, 5_000);

        assertThat(r.status()).isEqualTo(201);
        assertThat(api.balance(alice)).isEqualTo(45_000);
        assertThat(poolBalance()).isEqualTo(45_000);
    }

    @Test
    void s13_transferMoreThanBalance() {
        api.topUp(alice, 10_000);

        Response r = api.transfer(alice, bob, 10_001);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(api.balance(alice)).isEqualTo(10_000);
        assertThat(api.balance(bob)).isZero();
        assertThat(count("journals")).isEqualTo(1);
    }

    @Test
    void s14_withdrawMoreThanBalance() {
        api.topUp(alice, 10_000);

        Response r = api.withdraw(alice, 20_000);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(api.balance(alice)).isEqualTo(10_000);
        assertThat(poolBalance()).isEqualTo(10_000);
    }

    @Test
    void s15_invalidRequests() {
        api.topUp(alice, 10_000);

        assertError(api.transfer(alice, alice, 100), 400, "VALIDATION_FAILED");        // self-transfer
        assertError(api.transfer(alice, 9_999, 100), 404, "WALLET_NOT_FOUND");        // unknown receiver
        assertError(api.topUp(alice, 0), 400, "VALIDATION_FAILED");
        assertError(api.topUp(alice, -500), 400, "VALIDATION_FAILED");
        assertError(api.topUp(alice, 10_000_001), 400, "VALIDATION_FAILED");          // > ₹1,00,000
        assertError(api.post("/transfers", "k1", "{\"toWalletId\":2,\"amount\":5}"), 400, "VALIDATION_FAILED");
        assertError(api.post("/transfers", "k2", "not json"), 400, "VALIDATION_FAILED");

        long poolId = 1;
        assertError(api.topUp(poolId, 100), 404, "WALLET_NOT_FOUND");                 // pool is not a wallet

        assertThat(api.balance(alice)).isEqualTo(10_000);
    }

    @Test
    void walletCreationAndLookup() {
        Response dup = api.post("/wallets", null, "{\"userId\":\"alice\"}");
        assertError(dup, 409, "WALLET_EXISTS");

        Response w = api.get("/wallets/" + alice);
        assertThat(w.status()).isEqualTo(200);
        assertThat(w.str("$.userId")).isEqualTo("alice");
        assertThat(w.str("$.currency")).isEqualTo("INR");

        assertError(api.get("/wallets/9999"), 404, "WALLET_NOT_FOUND");
        assertError(api.get("/wallets/1"), 404, "WALLET_NOT_FOUND"); // pool is hidden
    }

    @Test
    void historyIsNewestFirstAndPaginated() {
        api.topUp(alice, 50_000);           // IN
        api.transfer(alice, bob, 20_000);   // OUT
        api.withdraw(alice, 5_000);         // OUT

        Response page1 = api.get("/wallets/" + alice + "/transactions?limit=2");
        assertThat(page1.status()).isEqualTo(200);
        List<String> types1 = com.jayway.jsonpath.JsonPath.read(page1.body(), "$.items[*].type");
        List<String> dirs1 = com.jayway.jsonpath.JsonPath.read(page1.body(), "$.items[*].direction");
        assertThat(types1).containsExactly("WITHDRAW", "TRANSFER");
        assertThat(dirs1).containsExactly("OUT", "OUT");

        long next = page1.num("$.nextBefore");
        Response page2 = api.get("/wallets/" + alice + "/transactions?limit=2&before=" + next);
        List<String> types2 = com.jayway.jsonpath.JsonPath.read(page2.body(), "$.items[*].type");
        assertThat(types2).containsExactly("TOP_UP");
        assertThat(page2.num("$.items[0].amount")).isEqualTo(50_000);
        assertThat(page2.str("$.items[0].direction")).isEqualTo("IN");
        assertThat((Object) com.jayway.jsonpath.JsonPath.read(page2.body(), "$.nextBefore")).isNull();
    }

    @Test
    void journalLookup() {
        long journalId = api.topUp(alice, 100).num("$.journalId");

        Response j = api.get("/journals/" + journalId);
        assertThat(j.status()).isEqualTo(200);
        assertThat(j.str("$.type")).isEqualTo("TOP_UP");
        List<Integer> amounts = com.jayway.jsonpath.JsonPath.read(j.body(), "$.postings[*].amount");
        assertThat(amounts).containsExactlyInAnyOrder(100, -100);

        assertError(api.get("/journals/9999"), 404, "JOURNAL_NOT_FOUND");
    }

    private static void assertError(Response r, int status, String code) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.code()).isEqualTo(code);
    }
}
