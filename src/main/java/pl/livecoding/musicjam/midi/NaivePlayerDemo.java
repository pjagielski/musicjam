package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Note;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import java.nio.file.Path;
import java.util.List;

/**
 * Step 3 of the workshop path. {@link SequencerDemo} handed a whole {@code Sequence} to a
 * {@code Sequencer} and let it schedule playback; here we parse the file ourselves (via
 * {@link MidiFileReader}, the same move as {@link InspectMidi}) and schedule playback ourselves
 * too (via {@link NaivePlayer}: one waiting thread per note, fired at an absolute
 * {@code System.nanoTime()} target). Playback still goes through the same built-in software
 * synthesizer as {@link SequencerDemo} ({@link MidiNoteOutput} wraps the same {@code Synthesizer}
 * {@code Sequencer} plays through) — only the scheduling changed, so any jitter you hear here is
 * scheduling jitter, not a different playback engine. No drums yet: that's step 5's
 * {@code PatternCompiler}, once there's a {@code Note} shape worth building by hand as well as
 * reading from a file.
 */
public class NaivePlayerDemo {
    private static final double BEATS_PER_BAR = 4.0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: .\\gradlew.bat naivePlayerDemo --args=\""
                    + "<file.mid> [trackIndex=1] [startBar=0] [bars=2] [loops=4]\"");
            return;
        }
        Path file = Path.of(args[0]);
        int trackIndex = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int startBar = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int bars = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        int loops = args.length > 4 ? Integer.parseInt(args[4]) : 4;

        Sequence sequence = MidiSystem.getSequence(file.toFile());
        double bpm = MidiFileReader.readTempo(sequence);
        int program = MidiFileReader.readProgram(sequence, trackIndex);
        double startBeat = startBar * BEATS_PER_BAR;
        double patternLength = bars * BEATS_PER_BAR;
        double endBeat = startBeat + patternLength;

        List<Note> notes = MidiFileReader.readTrack(sequence, trackIndex).stream()
                .filter(note -> note.beat() >= startBeat && note.beat() < endBeat)
                .map(note -> new Note(note.beat() - startBeat, note.voice(), note.durationBeats(), note.velocity()))
                .toList();

        System.out.printf("%s: sciezka %d, takty %d-%d, %.1f BPM, %d nut w petli x%d%n",
                file.getFileName(), trackIndex, startBar + 1, startBar + bars, bpm, notes.size(), loops);

        try (NaivePlayer player = NaivePlayer.openMidi(NaivePlayer.ThreadKind.PLATFORM, 0, program)) {
            System.out.println(player.play(notes, bpm, patternLength, loops));
        }
    }
}
