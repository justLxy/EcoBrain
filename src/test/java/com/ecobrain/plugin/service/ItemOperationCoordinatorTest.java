package com.ecobrain.plugin.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class ItemOperationCoordinatorTest {

    @Test
    void shouldSerializeOperationsForSameItemHash() throws Exception {
        ItemOperationCoordinator coordinator = new ItemOperationCoordinator();
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                try (ItemOperationCoordinator.Permit ignored = coordinator.acquire("hash-1")) {
                    firstAcquired.countDown();
                    Assertions.assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });

            Assertions.assertTrue(firstAcquired.await(5, TimeUnit.SECONDS));

            Future<Long> second = executor.submit(() -> {
                long start = System.nanoTime();
                try (ItemOperationCoordinator.Permit ignored = coordinator.acquire("hash-1")) {
                    secondEntered.set(true);
                    return System.nanoTime() - start;
                }
            });

            Thread.sleep(150L);
            Assertions.assertFalse(secondEntered.get(), "second same-item operation must wait for the first permit");

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);

            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(second.get(5, TimeUnit.SECONDS));
            Assertions.assertTrue(waitedMillis >= 100L, "second acquisition should have been serialized");
        } finally {
            executor.shutdownNow();
        }
    }
}
