package com.ecobrain.plugin.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serializes all stateful operations for a single item hash across threads.
 *
 * <p>We use a permit-based coordinator instead of a thread-owned lock so a
 * permit can safely be acquired on an async thread, held while a main-thread
 * Bukkit callback runs, and finally released from a later async callback.</p>
 */
public class ItemOperationCoordinator {
    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public Permit acquire(String itemHash) {
        if (itemHash == null || itemHash.isBlank()) {
            throw new IllegalArgumentException("itemHash must not be blank");
        }

        Semaphore semaphore = semaphores.computeIfAbsent(itemHash, ignored -> new Semaphore(1, true));
        boolean interrupted = false;
        while (true) {
            try {
                semaphore.acquire();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return new Permit(semaphore);
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
