package pl.livecoding.musicjam.scheduler;

import java.util.List;
import java.util.Locale;

public enum SchedulerKind {
    PLATFORM,
    VIRTUAL,
    POOL,
    SCOPED;

    public EventScheduler newScheduler() {
        return switch (this) {
            case PLATFORM -> new ThreadPerEventScheduler(
                    name(), Thread.ofPlatform().daemon(true).factory());
            case VIRTUAL -> new ThreadPerEventScheduler(name(), Thread.ofVirtual().factory());
            case POOL -> new PooledScheduler(Runtime.getRuntime().availableProcessors());
            case SCOPED -> new ScopedScheduler(name(), Thread.ofVirtual().factory());
        };
    }

    public static List<SchedulerKind> select(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "platform" -> List.of(PLATFORM);
            case "virtual" -> List.of(VIRTUAL);
            case "pool" -> List.of(POOL);
            case "scoped" -> List.of(SCOPED);
            case "all" -> List.of(PLATFORM, VIRTUAL, POOL, SCOPED);
            default -> throw new IllegalArgumentException(
                    "Unknown scheduler " + value + ", expected platform, virtual, pool, scoped or all");
        };
    }
}
