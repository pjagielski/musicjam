package pl.livecoding.musicjam.midi;

import java.nio.file.Path;
import java.util.List;

public record MidiFile(Path file, int resolution, double bpm, double totalBeats, List<TrackData> tracks) {

    public TrackData track(int index) {
        if (index < 0 || index >= tracks.size()) {
            throw new IllegalArgumentException(
                    "No track at index " + index + " (file has " + tracks.size() + ")");
        }
        return tracks.get(index);
    }
}
