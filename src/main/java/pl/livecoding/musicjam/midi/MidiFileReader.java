package pl.livecoding.musicjam.midi;

import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

public final class MidiFileReader {
    static final int META_TRACK_NAME = 0x03;
    static final int META_SET_TEMPO = 0x51;
    static final double DEFAULT_BPM = 120.0;

    private MidiFileReader() {
    }

    public static MidiFile read(Path file) throws IOException, InvalidMidiDataException {
        return read(MidiSystem.getSequence(file.toFile()), file);
    }

    static MidiFile read(Sequence sequence, Path file) {
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new IllegalArgumentException("Only PPQ-divided MIDI files are supported");
        }
        int resolution = sequence.getResolution();
        Track[] tracks = sequence.getTracks();
        List<TrackData> read = new ArrayList<>(tracks.length);
        double bpm = 0.0;

        for (int index = 0; index < tracks.length; index++) {
            TrackReading reading = readTrack(tracks[index], index, resolution);
            if (bpm == 0.0) {
                bpm = reading.bpm();
            }
            read.add(reading.track());
        }

        return new MidiFile(
                file,
                resolution,
                bpm == 0.0 ? DEFAULT_BPM : bpm,
                ticksToBeats(sequence.getTickLength(), resolution),
                List.copyOf(read));
    }

    private static TrackReading readTrack(Track track, int index, int resolution) {
        PlayingNotes playingNotes = new PlayingNotes();
        List<Note> notes = new ArrayList<>();
        String name = "";
        OptionalInt channel = OptionalInt.empty();
        OptionalInt program = OptionalInt.empty();
        double bpm = 0.0;

        for (int i = 0; i < track.size(); i++) {
            MidiEvent event = track.get(i);
            MidiMessage message = event.getMessage();

            if (message instanceof MetaMessage meta) {
                if (meta.getType() == META_TRACK_NAME && name.isEmpty()) {
                    name = new String(meta.getData());
                } else if (meta.getType() == META_SET_TEMPO && bpm == 0.0) {
                    bpm = bpmOf(meta);
                }
            } else if (message instanceof ShortMessage shortMessage) {
                int pitch = shortMessage.getData1();
                if (channel.isEmpty()) {
                    channel = OptionalInt.of(shortMessage.getChannel());
                }
                if (isProgramChange(shortMessage) && program.isEmpty()) {
                    program = OptionalInt.of(shortMessage.getData1());
                } else if (isNoteOn(shortMessage)) {
                    playingNotes.start(pitch, event.getTick(), shortMessage.getData2());
                } else if (isNoteOff(shortMessage)) {
                    playingNotes.finish(pitch)
                            .map(start -> noteBetween(start, pitch, event.getTick(), resolution))
                            .ifPresent(notes::add);
                }
            }
        }

        notes.sort(Comparator.comparingDouble(Note::beat)
                .thenComparingInt(note -> ((Voice.Pitch) note.voice()).midiNote()));
        return new TrackReading(new TrackData(index, name, channel, program, List.copyOf(notes)), bpm);
    }

    private static Note noteBetween(
            PlayingNotes.PlayingNote start, int pitch, long endTick, int resolution) {
        double durationBeats = ticksToBeats(endTick - start.tick(), resolution);
        if (durationBeats <= 0.0) {
            return null;
        }
        return new Note(
                ticksToBeats(start.tick(), resolution),
                new Voice.Pitch(pitch),
                durationBeats,
                start.velocity() / 127f);
    }

    static boolean isNoteOn(ShortMessage message) {
        return message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0;
    }

    static boolean isProgramChange(ShortMessage message) {
        return message.getCommand() == ShortMessage.PROGRAM_CHANGE;
    }

    static boolean isNoteOff(ShortMessage message) {
        return message.getCommand() == ShortMessage.NOTE_OFF
                || (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0);
    }

    static double bpmOf(MetaMessage tempo) {
        byte[] data = tempo.getData();
        int microsecondsPerQuarterNote =
                ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
        return 60_000_000.0 / microsecondsPerQuarterNote;
    }

    /**
     * Ticks as beats. A MIDI file counts time in ticks, {@code resolution} of them (its PPQ) to a
     * quarter note, and a beat here is a quarter note - so it serves a moment and a length alike.
     */
    static double ticksToBeats(long ticks, int resolution) {
        return ticks / (double) resolution;
    }

    private record TrackReading(TrackData track, double bpm) {
    }
}
