package pl.livecoding.musicjam.midi;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.nio.file.Path;

class InspectMidi {
    private static final double BEATS_PER_BAR = 4.0;

    static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java InspectMidi.java <file.mid>");
            return;
        }

        Path file = Path.of(args[0]);
        Sequence sequence = MidiSystem.getSequence(file.toFile());
        int ppq = sequence.getResolution();
        double bpm = readTempo(sequence);
        double totalBeats = sequence.getTickLength() / (double) ppq;

        System.out.printf("Plik: %s%n", file.getFileName());
        System.out.printf("PPQ: %d, tempo: %.1f BPM, dlugosc: %.1f taktow (4/4)%n",
                ppq, bpm, totalBeats / BEATS_PER_BAR);
        System.out.println("Sciezki:");

        Track[] tracks = sequence.getTracks();
        for (int trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
            Track track = tracks[trackIndex];
            String name = trackName(track);
            int program = -1;
            int channel = -1;
            int noteCount = 0;
            int minPitch = 127;
            int maxPitch = 0;
            long firstNoteTick = -1;

            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage shortMessage) {
                    if (channel < 0) {
                        channel = shortMessage.getChannel();
                    }
                    if (shortMessage.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                        program = shortMessage.getData1();
                    } else if (shortMessage.getCommand() == ShortMessage.NOTE_ON
                            && shortMessage.getData2() > 0) {
                        noteCount++;
                        minPitch = Math.min(minPitch, shortMessage.getData1());
                        maxPitch = Math.max(maxPitch, shortMessage.getData1());
                        if (firstNoteTick < 0) {
                            firstNoteTick = event.getTick();
                        }
                    }
                }
            }

            if (noteCount == 0) {
                System.out.printf("  %2d: %-24s (brak nut)%n", trackIndex, name);
                continue;
            }
            double firstBar = (double) firstNoteTick / ppq / BEATS_PER_BAR + 1;
            System.out.printf(
                    "  %2d: %-24s kanal=%-3d program=%-3d nut=%-5d zakres=[%d,%d] pierwsza nuta: takt %.1f%n",
                    trackIndex, name, channel, program, noteCount, minPitch, maxPitch, firstBar);
        }
    }

    private static double readTempo(Sequence sequence) {
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof MetaMessage metaMessage && metaMessage.getType() == 0x51) {
                    byte[] data = metaMessage.getData();
                    int microsecondsPerQuarterNote =
                            ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                    return 60_000_000.0 / microsecondsPerQuarterNote;
                }
            }
        }
        return 120.0;
    }

    private static String trackName(Track track) {
        for (int i = 0; i < track.size(); i++) {
            MidiMessage message = track.get(i).getMessage();
            if (message instanceof MetaMessage metaMessage && metaMessage.getType() == 0x03) {
                return new String(metaMessage.getData());
            }
        }
        return "";
    }
}
