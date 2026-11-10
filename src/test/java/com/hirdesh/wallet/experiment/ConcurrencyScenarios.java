package com.hirdesh.wallet.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.hirdesh.wallet.ledger.locking.AccountLocking;
import com.hirdesh.wallet.ledger.locking.LockingMetrics;
import com.hirdesh.wallet.support.ConcurrentRun;
import com.hirdesh.wallet.support.IntegrationTest;
import com.hirdesh.wallet.support.LedgerCheck;

/**
 * S-30..S-33 written once, run against every locking strategy by the subclasses.
 * Amounts in paise: ₹1 = 100.
 */
abstract class ConcurrencyScenarios extends IntegrationTest {

    @Autowired LockingMetrics metrics;
    @Autowired AccountLocking locking;

    /** Everything a test needs to judge one scenario run. */
    record Outcome(String scenario, ConcurrentRun run, LedgerCheck check, long conflicts, long exhausted,
                   long[] userBalances) {

        long ok() { return run.count(201); }
        long rejected() { return run.count(422); }
        long retryLater() { return run.count(503); }
    }

    @BeforeEach
    void resetMetrics() {
        metrics.reset();
    }

    /** S-30: A has ₹100; 100 parallel ₹1 transfers A→B. */
    Outcome s30() {
        long a = api.createWallet("a"), b = api.createWallet("b");
        api.topUp(a, 10_000);
        ConcurrentRun run = ConcurrentRun.of(100, i -> api.transfer(a, b, 100));
        return outcome("S-30 100×₹1 A→B (A has ₹100)", run, a, b);
    }

    /** S-31: A has only ₹60; 100 parallel ₹1 transfers. Exactly 60 should succeed. */
    Outcome s31() {
        long a = api.createWallet("a"), b = api.createWallet("b");
        api.topUp(a, 6_000);
        ConcurrentRun run = ConcurrentRun.of(100, i -> api.transfer(a, b, 100));
        return outcome("S-31 100×₹1 A→B (A has ₹60)", run, a, b);
    }

    /** S-32: 50 A→B and 50 B→A at the same time — the deadlock trap. */
    Outcome s32() {
        long a = api.createWallet("a"), b = api.createWallet("b");
        api.topUp(a, 5_000);
        api.topUp(b, 5_000);
        ConcurrentRun run = ConcurrentRun.of(100, i -> i % 2 == 0 ? api.transfer(a, b, 100) : api.transfer(b, a, 100));
        return outcome("S-32 50 A→B + 50 B→A", run, a, b);
    }

    /** S-33: 10 wallets × ₹100, 200 random transfers of ₹0.01–₹5 between random pairs. */
    Outcome s33() {
        int n = 10;
        long[] w = new long[n];
        for (int i = 0; i < n; i++) {
            w[i] = api.createWallet("w" + i);
            api.topUp(w[i], 10_000);
        }
        Random rnd = new Random(42); // fixed seed → same workload every run
        List<long[]> plan = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int from = rnd.nextInt(n), to = (from + 1 + rnd.nextInt(n - 1)) % n;
            plan.add(new long[] {w[from], w[to], 1 + rnd.nextInt(500)});
        }
        ConcurrentRun run = ConcurrentRun.of(plan.size(), i -> api.transfer(plan.get(i)[0], plan.get(i)[1], plan.get(i)[2]));
        return outcome("S-33 200 random transfers, 10 wallets", run, w);
    }

    /** Sum of all user wallets as the API reports them (stored balances). */
    long totalUserBalance(Outcome o) {
        long sum = 0;
        for (long b : o.userBalances()) {
            sum += b;
        }
        return sum;
    }

    private Outcome outcome(String name, ConcurrentRun run, long... wallets) {
        long[] balances = new long[wallets.length];
        for (int i = 0; i < wallets.length; i++) {
            balances[i] = api.balance(wallets[i]);
        }
        Outcome o = new Outcome(name, run, LedgerCheck.run(jdbc), metrics.conflicts(), metrics.exhaustedRetries(),
                balances);
        print(o);
        return o;
    }

    private void print(Outcome o) {
        System.out.printf("[M3] %-12s | %-40s | 201=%3d 422=%3d 503=%3d | conflicts=%4d exhausted=%3d | %5d ms | "
                        + "balances=%s | %s%n",
                locking.getClass().getSimpleName(), o.scenario(), o.ok(), o.rejected(), o.retryLater(),
                o.conflicts(), o.exhausted(), o.run().millis(), java.util.Arrays.toString(o.userBalances()),
                o.check());
    }
}
