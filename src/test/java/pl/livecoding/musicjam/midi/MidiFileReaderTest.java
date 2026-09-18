package pl.livecoding.musicjam.midi;

import org.junit.jupiter.api.Test;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exercise of step 2, in the order the tests are written here: make them pass one by one and
 * {@code MidiFileReader.read} grows into the reader every later step uses. Every sequence is built
 * in code, so nothing here depends on a file or on a sound device.
 */
class MidiFileReaderTest {

    private static final int PPQ = 480;
    private static final Path FILE = Path.of("song.mid");

    @Test
    void aPairOfEventsBecomesOneNoteMeasuredInBeats() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 960, 60, 100);
        noteOff(track, 1440, 60);

        List<Note> notes = MidiFileReader.read(sequence, FILE).track(0).notes();

        assertEquals(1, notes.size());
        Note note = notes.get(0);
        assertEquals(2.0, note.beat(), "tick 960 at 480 ticks per quarter note is beat 2");
        assertEquals(1.0, note.durationBeats());
        assertEquals(new Voice.Pitch(60), note.voice());
        assertEquals(100 / 127f, note.velocity(), 1e-6f);
    }

    @Test
    void aNoteOnWithVelocityZeroEndsTheNote() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 0, 60, 80);
        noteOn(track, 480, 60, 0);

        List<Note> notes = MidiFileReader.read(sequence, FILE).track(0).notes();

        assertEquals(1, notes.size());
        assertEquals(1.0, notes.get(0).durationBeats());
    }

    @Test
    void theSamePitchCanBeStruckAgainBeforeTheFirstOneIsReleased() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 0, 60, 80);
        noteOn(track, 240, 60, 80);
        noteOff(track, 480, 60);
        noteOff(track, 720, 60);

        List<Note> notes = MidiFileReader.read(sequence, FILE).track(0).notes();

        assertEquals(2, notes.size(), "the second hit cannot swallow the first one");
        assertEquals(0.0, notes.get(0).beat());
        assertEquals(1.0, notes.get(0).durationBeats(), "the first note-off closes the first note-on");
        assertEquals(0.5, notes.get(1).beat());
        assertEquals(1.0, notes.get(1).durationBeats());
    }

    @Test
    void notesComeOutInTheOrderTheyAreHeard() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 480, 72, 80);
        noteOff(track, 720, 72);
        noteOn(track, 0, 64, 80);
        noteOff(track, 240, 64);

        List<Note> notes = MidiFileReader.read(sequence, FILE).track(0).notes();

        assertEquals(List.of(0.0, 1.0), notes.stream().map(Note::beat).toList());
    }

    @Test
    void sortsNotesByStartBeatThenPitchEvenWhenTheyEndInAnotherOrder() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 0, 72, 80);
        noteOn(track, 0, 60, 80);
        noteOn(track, 480, 64, 80);
        noteOff(track, 720, 64);
        noteOff(track, 960, 72);
        noteOff(track, 1440, 60);

        List<Note> notes = MidiFileReader.read(sequence, FILE).track(0).notes();

        assertEquals(List.of(0.0, 0.0, 1.0), notes.stream().map(Note::beat).toList());
        assertEquals(List.of(60, 72, 64), notes.stream()
                .map(note -> ((Voice.Pitch) note.voice()).midiNote())
                .toList());
    }

    @Test
    void readsTheTrackNameItsChannelAndItsInstrument() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(new MetaMessage(0x03, "Pizz Strings".getBytes(), 12), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 1, 45, 0), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 1, 46, 0), 1));
        noteOn(track, 0, 60, 80);
        noteOff(track, 480, 60);

        TrackData read = MidiFileReader.read(sequence, FILE).track(0);

        assertEquals("Pizz Strings", read.name());
        assertEquals(OptionalInt.of(1), read.channel());
        assertEquals(OptionalInt.of(45), read.program());
    }

    @Test
    void hasNoProgramWhenTheTrackHasNoProgramChange() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 0, 60, 80);

        assertEquals(OptionalInt.empty(), MidiFileReader.read(sequence, FILE).track(0).program());
    }

    @Test
    void takesTheTempoFromTheFileAndFallsBackTo120() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        // 640000 microseconds per quarter note is 93.75 BPM
        track.add(new MidiEvent(new MetaMessage(0x51, new byte[] {0x09, (byte) 0xC4, 0x00}, 3), 0));
        noteOn(track, 0, 60, 80);
        noteOff(track, 480, 60);

        assertEquals(93.75, MidiFileReader.read(sequence, FILE).bpm(), 1e-9);

        Sequence withoutTempo = new Sequence(Sequence.PPQ, PPQ);
        Track plain = withoutTempo.createTrack();
        noteOn(plain, 0, 60, 80);
        noteOff(plain, 480, 60);

        assertEquals(120.0, MidiFileReader.read(withoutTempo, FILE).bpm(), 1e-9);
    }

    @Test
    void skipsANoteOffThatClosesNothingAndANoteWithNoLength() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOff(track, 0, 55);
        noteOn(track, 480, 60, 80);
        noteOff(track, 480, 60);

        assertTrue(MidiFileReader.read(sequence, FILE).track(0).notes().isEmpty());
    }

    @Test
    void measuresTheWholeFileInBeatsToo() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track track = sequence.createTrack();
        noteOn(track, 0, 60, 80);
        noteOff(track, 1920, 60);

        assertEquals(4.0, MidiFileReader.read(sequence, FILE).totalBeats(), 1e-9);
        assertEquals(PPQ, MidiFileReader.read(sequence, FILE).resolution());
    }

    @Test
    void rejectsAFileThatIsNotDividedIntoTicksPerQuarterNote() throws Exception {
        Sequence smpte = new Sequence(Sequence.SMPTE_24, 40);
        smpte.createTrack();

        assertThrows(IllegalArgumentException.class, () -> MidiFileReader.read(smpte, FILE));
    }

    private static void noteOn(Track track, long tick, int pitch, int velocity) throws Exception {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 1, pitch, velocity), tick));
    }

    private static void noteOff(Track track, long tick, int pitch) throws Exception {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 1, pitch, 0), tick));
    }
}
