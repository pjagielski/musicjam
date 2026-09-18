package pl.livecoding.musicjam.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class ThreadPerEventScheduler implements EventScheduler {

    private final String label;
    private final ThreadFactory threads;
    private final List<Thread> workers = new ArrayList<>();
    private long startNanos;

    ThreadPerEventScheduler(String label, ThreadFactory threads) {
        this.label = label;
        this.threads = threads;
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
        Thread worker = threads.newThread(() -> {
            try {
                sleepUntil(targetNanos);
                task.runAt(targetNanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        workers.add(worker);
        worker.start();
    }

    @Override
    public void awaitDone() throws InterruptedException {
        for (Thread worker : workers) {
            worker.join();
        }
    }

    private static void sleepUntil(long targetNanos) throws InterruptedException {
        long remaining;
        while ((remaining = targetNanos - System.nanoTime()) > 0L) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }
}
