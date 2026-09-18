package pl.livecoding.musicjam.midi;

import java.util.Locale;

public record TimingReport(
        String label,
        long events,
        long setupMicros,
        long minMicros,
        double averageMicros,
        long maxMicros
) {

    static TimingReport empty(String label) {
        return new TimingReport(label, 0, 0, 0, 0.0, 0);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "%-9s %5d events, setup=%6d us, wake-up lateness min=%5d us, avg=%5.0f us, max=%6d us",
                label + ":", events, setupMicros, minMicros, averageMicros, maxMicros);
    }
}
