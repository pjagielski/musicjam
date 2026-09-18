package pl.livecoding.musicjam.scheduler;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class ScopedScheduler implements EventScheduler {

    private final String label;
    private final StructuredTaskScope<Void, Void> scope;
    private long startNanos;

    ScopedScheduler(String label, ThreadFactory threads) {
        this.label = label;
        this.scope = StructuredTaskScope.open(
                Joiner.awaitAllSuccessfulOrThrow(),
                config -> config.withName(label).withThreadFactory(threads));
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public void begin(long startupDelayNanos) {
        startNanos = System.nanoTime() + startupDelayNanos;
    }

    @Override
    public void submit(long offsetNanos, TimedTask task) {
        long targetNanos = startNanos + offsetNanos;
        scope.fork(() -> {
            sleepUntil(targetNanos);
            task.runAt(targetNanos);
            return null;
        });
    }

    @Override
    public void awaitDone() throws InterruptedException {
        scope.join();
    }

    @Override
    public void close() {
        scope.close();
    }

    private static void sleepUntil(long targetNanos) throws InterruptedException {
        long remaining;
        while ((remaining = targetNanos - System.nanoTime()) > 0L) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }
}
