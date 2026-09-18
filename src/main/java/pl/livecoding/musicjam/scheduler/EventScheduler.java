package pl.livecoding.musicjam.scheduler;

public interface EventScheduler extends AutoCloseable {

    String label();

    void begin(long startupDelayNanos);

    void submit(long offsetNanos, TimedTask task);

    void awaitDone() throws InterruptedException;

    @Override
    default void close() {
    }

    @FunctionalInterface
    interface TimedTask {
        void runAt(long targetNanos);
    }
}
