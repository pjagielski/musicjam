package pl.livecoding.musicjam.midi;

public record TimingReport(
        NaivePlayer.ThreadKind threadKind,
        long events,
        long setupMicros,
        long minMicros,
        double averageMicros,
        long maxMicros
) {

    static TimingReport empty(NaivePlayer.ThreadKind threadKind) {
        return new TimingReport(threadKind, 0, 0, 0, 0.0, 0);
    }

    @Override
    public String toString() {
        return "%s: setup=%d us, jitter min=%d us, avg=%.0f us, max=%d us (%d hitow)"
                .formatted(threadKind, setupMicros, minMicros, averageMicros, maxMicros, events);
    }
}
