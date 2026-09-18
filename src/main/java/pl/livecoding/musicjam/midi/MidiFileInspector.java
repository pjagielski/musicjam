package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;

import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Locale;

public class MidiFileInspector {
    static final double BEATS_PER_BAR = 4.0;
    private static final int NAME_WIDTH = 24;

    private final MidiFile midi;

    public MidiFileInspector(MidiFile midi) {
        this.midi = midi;
    }

    public void printOverview() {
        System.out.printf(Locale.ROOT, "File: %s%n", midi.file().getFileName());
        System.out.printf(Locale.ROOT, "PPQ: %d, tempo: %.1f BPM, length: %.1f bars (4/4)%n",
                midi.resolution(), midi.bpm(), midi.totalBeats() / BEATS_PER_BAR);
        System.out.println("Tracks:");

        for (TrackData track : midi.tracks()) {
            List<Note> notes = track.notes();
            if (notes.isEmpty()) {
                System.out.printf(Locale.ROOT, "  %2d: %-24s (no notes)%n", track.index(), label(track.name()));
                continue;
            }
            IntSummaryStatistics pitches = notes.stream()
                    .mapToInt(MidiFileInspector::pitchOf)
                    .summaryStatistics();
            System.out.printf(Locale.ROOT,
                    "  %2d: %-24s ch=%-3s prog=%-4s notes=%-5d %-16s first note: bar %.1f%n",
                    track.index(),
                    label(track.name()),
                    channelLabel(track),
                    programLabel(track),
                    notes.size(),
                    "range=[" + noteName(pitches.getMin()) + ".." + noteName(pitches.getMax()) + "]",
                    notes.get(0).beat() / BEATS_PER_BAR + 1);
        }
    }

    public void printTrack(int trackIndex) {
        TrackData track = midi.track(trackIndex);

        System.out.printf(Locale.ROOT, "Track %d: %s (ch=%s, prog=%s), %d notes%n",
                track.index(), track.name(), channelLabel(track), programLabel(track), track.notes().size());
        System.out.printf(Locale.ROOT, "%8s  %8s  %-9s  %6s  %5s%n",
                "beat", "bar.beat", "pitch", "dur", "vel");

        for (Note note : track.notes()) {
            int pitch = pitchOf(note);
            System.out.printf(Locale.ROOT, "%8.3f  %8s  %-9s  %6.3f  %5.2f%n",
                    note.beat(),
                    barPosition(note.beat()),
                    noteName(pitch) + " (" + pitch + ")",
                    note.durationBeats(),
                    note.velocity());
        }
    }

    static String noteName(int midiNote) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        return names[midiNote % 12] + (midiNote / 12 - 1);
    }

    private static String barPosition(double beat) {
        return String.format(Locale.ROOT, "%d:%.2f",
                (int) (beat / BEATS_PER_BAR) + 1, beat % BEATS_PER_BAR + 1);
    }

    private static String label(String name) {
        return name.length() <= NAME_WIDTH ? name : name.substring(0, NAME_WIDTH - 3) + "...";
    }

    private static String channelLabel(TrackData track) {
        return track.channel().isPresent() ? String.valueOf(track.channel().getAsInt()) : "-";
    }

    private static String programLabel(TrackData track) {
        return track.program().isPresent() ? String.valueOf(track.program().getAsInt()) : "-";
    }

    private static int pitchOf(Note note) {
        return ((Voice.Pitch) note.voice()).midiNote();
    }
}
