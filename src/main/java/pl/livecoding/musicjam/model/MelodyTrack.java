package pl.livecoding.musicjam.model;

import java.util.List;
import java.util.Objects;

/**
 * A pre-loaded phrase of notes — typically a windowed excerpt read from a MIDI file (see
 * {@code MidiFileReader} in the {@code midi} package). Unlike {@link DrumTrack}, a
 * {@code MelodyTrack} already spans its full {@code patternLengthBeats} and is never tiled; it's
 * the other tracks (and the song's total length) that adapt to it.
 */
public record MelodyTrack(List<Note> notes, double patternLengthBeats, float gain) implements Track {

    public MelodyTrack {
        Objects.requireNonNull(notes, "notes");
        notes = List.copyOf(notes);
        if (patternLengthBeats <= 0.0) {
            throw new IllegalArgumentException("Pattern length must be positive");
        }
        if (!Float.isFinite(gain) || gain < 0.0f || gain > 1.0f) {
            throw new IllegalArgumentException("Gain must be between 0 and 1");
        }
    }
}
