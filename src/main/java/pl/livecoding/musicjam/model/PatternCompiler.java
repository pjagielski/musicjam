package pl.livecoding.musicjam.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PatternCompiler {

    private PatternCompiler() {
    }

    /**
     * Compiles every track of a song into one loop's worth of notes. A {@link DrumTrack} is one
     * bar ({@code song.beatsPerBar()}) and repeats to fill {@link #totalBeats}; a
     * {@link MelodyTrack} already spans the full length and contributes once.
     */
    public static List<Note> compile(Song song) {
        double totalBeats = totalBeats(song);
        var notes = new ArrayList<Note>();
        for (Track track : song.tracks()) {
            switch (track) {
                case DrumTrack drumTrack -> notes.addAll(compileDrumTrack(drumTrack, song.beatsPerBar(), totalBeats));
                case MelodyTrack melodyTrack -> notes.addAll(compileMelodyTrack(melodyTrack));
            }
        }
        notes.sort(Comparator.comparingDouble(Note::beat));
        return List.copyOf(notes);
    }

    /**
     * The length of one loop of this song, in beats: {@code beatsPerBar} for a pattern-only song,
     * or the longest {@link MelodyTrack}'s length when the song has one — drum patterns tile to
     * match it.
     */
    public static double totalBeats(Song song) {
        return song.tracks().stream()
                .filter(track -> track instanceof MelodyTrack)
                .mapToDouble(track -> ((MelodyTrack) track).patternLengthBeats())
                .max()
                .orElse(song.beatsPerBar());
    }

    private static List<Note> compileDrumTrack(DrumTrack track, double beatsPerBar, double totalBeats) {
        String steps = track.steps();
        double durationBeats = beatsPerBar / steps.length();
        int repeats = Math.max(1, (int) Math.round(totalBeats / beatsPerBar));
        var notes = new ArrayList<Note>();
        for (int repeat = 0; repeat < repeats; repeat++) {
            double barOffset = repeat * beatsPerBar;
            for (int step = 0; step < steps.length(); step++) {
                float accent = accentOf(steps.charAt(step));
                if (accent > 0.0f) {
                    double beat = barOffset + (double) step * beatsPerBar / steps.length();
                    notes.add(new Note(beat, track.drum(), durationBeats, track.gain() * accent));
                }
            }
        }
        return notes;
    }

    private static List<Note> compileMelodyTrack(MelodyTrack track) {
        return track.notes().stream()
                .map(note -> new Note(note.beat(), note.voice(), note.durationBeats(), note.velocity() * track.gain()))
                .toList();
    }

    private static float accentOf(char step) {
        return switch (step) {
            case 'X' -> 1.0f;
            case 'x' -> 0.8f;
            case 'o' -> 0.5f;
            case '.', '-', ' ' -> 0.0f;
            default -> throw new IllegalArgumentException("Unknown pattern character: " + step);
        };
    }
}
