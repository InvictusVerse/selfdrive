package com.selfdriving.service;

import java.lang.System.Logger.Level;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One background thread for database writes that come from the simulation, so the 120 Hz
 * simulation thread never waits for the disk. Writes run in the order they were queued.
 */
public final class DbWriter implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DbWriter.class.getName());

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "database-writer");
        t.setDaemon(true);
        return t;
    });

    /** Queues a write. A failure is logged and does not stop later writes. */
    public void run(String what, Runnable write) {
        if (executor.isShutdown()) {
            return;
        }
        executor.execute(() -> {
            try {
                write.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not save " + what, e);
            }
        });
    }

    /** Waits until everything queued so far has been written (tests, shutdown). */
    public void flush() {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        run("flush", done::countDown);
        try {
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Finishes queued writes (up to 5 s) and stops the thread. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
