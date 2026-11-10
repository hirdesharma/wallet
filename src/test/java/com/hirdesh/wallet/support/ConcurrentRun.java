package com.hirdesh.wallet.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import com.hirdesh.wallet.support.ApiClient.Response;

/**
 * Fires N requests at the same instant: every task is parked on a start gate,
 * then released together. Virtual threads make 100+ concurrent callers cheap.
 */
public record ConcurrentRun(List<Response> responses, long millis) {

    public static ConcurrentRun of(int n, IntFunction<Response> task) {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>(n);
        long start;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.apply(idx);
                }));
            }
            start = System.nanoTime();
            startGate.countDown();
        } // close() waits for all tasks
        long millis = (System.nanoTime() - start) / 1_000_000;

        List<Response> responses = new ArrayList<>(n);
        for (Future<Response> f : futures) {
            try {
                responses.add(f.get());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return new ConcurrentRun(responses, millis);
    }

    public long count(int status) {
        return responses.stream().filter(r -> r.status() == status).count();
    }
}
