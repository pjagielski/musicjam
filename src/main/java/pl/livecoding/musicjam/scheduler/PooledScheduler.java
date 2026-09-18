package pl.livecoding.musicjam.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class PooledScheduler implements EventScheduler {

    private final int poolSize;
    private final ScheduledThreadPoolExecutor pool;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private long startNanos;

    PooledScheduler(int poolSize) {
        this.poolSize = poolSize;
        this.pool = new ScheduledThreadPoolExecutor(poolSize);
        this.pool.prestartAllCoreThreads();
    }

    @Override
    public String label() {
        return "POOL(" + poolSize + ")";
    }

    @Override
    public void begin(long startupDelayNanos) {
        startNanos = System.nanoTime() + startupDelayNanos;
    }

    @Override
    public void submit(long offsetNanos, TimedTask task) {
        long targetNanos = startNanos + offsetNanos;
        tasks.add(pool.schedule(
                () -> task.runAt(targetNanos),
                targetNanos - System.nanoTime(),
                TimeUnit.NANOSECONDS));
    }

    @Override
    public void awaitDone() throws InterruptedException {
        pool.shutdown();
        for (ScheduledFuture<?> task : tasks) {
            try {
                task.get();
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(cause);
            }
        }
        if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
            pool.shutdownNow();
            throw new IllegalStateException("Scheduled MIDI events did not finish within one hour");
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
