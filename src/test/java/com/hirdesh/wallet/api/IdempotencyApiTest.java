package com.hirdesh.wallet.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hirdesh.wallet.idempotency.IdempotencyRepository;
import com.hirdesh.wallet.idempotency.RequestHasher;
import com.hirdesh.wallet.support.ApiClient;
import com.hirdesh.wallet.support.ApiClient.Response;
import com.hirdesh.wallet.support.IntegrationTest;

/** S-20 .. S-23 plus lease/fencing behaviour from ADR-004. */
class IdempotencyApiTest extends IntegrationTest {

    @Autowired IdempotencyRepository idemRepo;
    @Autowired RequestHasher hasher;

    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        alice = api.createWallet("alice");
        bob = api.createWallet("bob");
        api.topUp(alice, 100_000);
    }

    @Test
    void s20_sequentialRetriesMoveMoneyOnce() {
        String key = ApiClient.newKey();

        List<Response> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(api.transfer(alice, bob, 20_000, key));
        }

        assertThat(api.balance(alice)).isEqualTo(80_000);
        assertThat(api.balance(bob)).isEqualTo(20_000);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(201);
            assertThat(r.body()).isEqualTo(responses.getFirst().body()); // byte-identical
        });
        assertThat(responses.getFirst().replayed()).isFalse();
        assertThat(responses.subList(1, 5)).allMatch(Response::replayed);
    }

    @Test
    void s21_concurrentDuplicatesMoveMoneyOnce() throws Exception {
        String key = ApiClient.newKey();
        int threads = 20;
        CountDownLatch startGate = new CountDownLatch(1);

        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();               // release all 20 at the same instant
                    return api.transfer(alice, bob, 20_000, key);
                }));
            }
            startGate.countDown();
        }

        List<Response> responses = new ArrayList<>();
        for (Future<Response> f : futures) {
            responses.add(f.get());
        }

        System.out.printf("S-21 outcome: original=%d replayed=%d inProgress409=%d%n",
                responses.stream().filter(r -> r.status() == 201 && !r.replayed()).count(),
                responses.stream().filter(Response::replayed).count(),
                responses.stream().filter(r -> r.status() == 409).count());

        // Money moved exactly once…
        assertThat(api.balance(alice)).isEqualTo(80_000);
        assertThat(api.balance(bob)).isEqualTo(20_000);
        assertThat(count("journals")).isEqualTo(2); // setup top-up + ONE transfer

        // …and every caller got either the result or "still in progress, retry".
        assertThat(responses).allSatisfy(r ->
                assertThat(r.status() == 201 || (r.status() == 409 && r.code().equals("REQUEST_IN_PROGRESS")))
                        .as(r.status() + " " + r.body()).isTrue());
        assertThat(responses).filteredOn(r -> r.status() == 201 && !r.replayed()).hasSize(1);
    }

    @Test
    void s22_sameKeyDifferentRequestIsRejected() {
        String key = ApiClient.newKey();
        api.transfer(alice, bob, 20_000, key);

        Response r = api.transfer(alice, bob, 30_000, key);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(api.balance(bob)).isEqualTo(20_000);
    }

    @Test
    void s22_sameKeyOnDifferentOperationIsRejected() {
        String key = ApiClient.newKey();
        api.transfer(alice, bob, 20_000, key);

        Response r = api.topUp(alice, 20_000, key);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void s23_missingKeyIsRejected() {
        Response r = api.post("/transfers", null,
                "{\"fromWalletId\":" + alice + ",\"toWalletId\":" + bob + ",\"amount\":100}");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("IDEMPOTENCY_KEY_MISSING");
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void businessFailureIsStoredAndReplayed() {
        String key = ApiClient.newKey();
        Response first = api.transfer(alice, bob, 500_000, key);   // alice has only 100_000
        assertThat(first.status()).isEqualTo(422);

        api.topUp(alice, 1_000_000);                               // now she could afford it…
        Response retry = api.transfer(alice, bob, 500_000, key);   // …but same key = same answer

        assertThat(retry.status()).isEqualTo(422);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(api.balance(bob)).isZero();

        // A NEW key is a new attempt.
        assertThat(api.transfer(alice, bob, 500_000).status()).isEqualTo(201);
    }

    @Test
    void validationFailureIsNotStored() {
        String key = ApiClient.newKey();
        assertThat(api.transfer(alice, bob, 0, key).status()).isEqualTo(400);

        // the key was never claimed, so it's still usable
        assertThat(api.transfer(alice, bob, 100, key).status()).isEqualTo(201);
    }

    @Test
    void inFlightKeyReturns409() {
        String key = ApiClient.newKey();
        String hash = hasher.hash("TRANSFER", alice, bob, 20_000L);
        idemRepo.tryClaim(key, hash, UUID.randomUUID(), 30);   // someone else is "working" on it

        Response r = api.transfer(alice, bob, 20_000, key);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("REQUEST_IN_PROGRESS");
        assertThat(api.balance(bob)).isZero();
    }

    @Test
    void expiredLeaseIsTakenOver() {
        // Simulates: app claimed the key, then crashed before finishing.
        String key = ApiClient.newKey();
        String hash = hasher.hash("TRANSFER", alice, bob, 20_000L);
        idemRepo.tryClaim(key, hash, UUID.randomUUID(), 30);
        jdbc.sql("UPDATE idempotency_keys SET locked_until = now() - interval '1 second' WHERE key = :k")
                .param("k", key).update();

        Response r = api.transfer(alice, bob, 20_000, key);

        assertThat(r.status()).isEqualTo(201);
        assertThat(api.balance(bob)).isEqualTo(20_000);
    }

    @Test
    void fencingTokenStopsAStaleOwnerFromCompleting() {
        String key = ApiClient.newKey();
        UUID slowOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();

        idemRepo.tryClaim(key, "h", slowOwner, 30);
        jdbc.sql("UPDATE idempotency_keys SET locked_until = now() - interval '1 second' WHERE key = :k")
                .param("k", key).update();
        assertThat(idemRepo.tryTakeover(key, newOwner, 30)).isTrue();

        // the slow original finally tries to complete → refused (its Tx2 would roll back)
        assertThat(idemRepo.complete(key, slowOwner, 201, "{}", null)).isZero();
        assertThat(idemRepo.complete(key, newOwner, 201, "{}", null)).isEqualTo(1);
    }
}
