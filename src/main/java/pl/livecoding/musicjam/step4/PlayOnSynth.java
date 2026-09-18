package pl.livecoding.musicjam.step4;

import pl.livecoding.musicjam.Config;
import pl.livecoding.musicjam.midi.MidiFile;
import pl.livecoding.musicjam.midi.MidiFileReader;
import pl.livecoding.musicjam.midi.MidiPlayer;
import pl.livecoding.musicjam.midi.TrackData;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.scheduler.SchedulerKind;

import java.util.List;
import java.util.Locale;

class PlayOnSynth {
    private static final double BEATS_PER_BAR = 4.0;

    static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        String device = config.midiDevice().orElseThrow(() -> new IllegalArgumentException(
                "No MIDI device to play on: set midiDevice= in the properties file or pass "
                        + "--midiDevice <name> (listMidiDevices shows the names)"));
        MidiFile midi = MidiFileReader.read(config.file());
        TrackData track = midi.track(config.track().orElseThrow(() -> new IllegalArgumentException(
                "No track to play: set track= in the properties file or pass --track <n>")));

        double patternLengthBeats = config.bars() * BEATS_PER_BAR;
        List<Note> notes = track.window(config.fromBar() * BEATS_PER_BAR, patternLengthBeats);
        if (notes.isEmpty()) {
            System.out.printf(Locale.ROOT, "Track %d has no notes in bars %d-%d%n",
                    track.index(), config.fromBar() + 1, config.fromBar() + config.bars());
            return;
        }

        System.out.printf(Locale.ROOT,
                "%s track %d (%s): bars %d-%d, %.1f BPM, %d notes, %d loops, on \"%s\", channel %d%n",
                midi.file().getFileName(), track.index(), track.name(),
                config.fromBar() + 1, config.fromBar() + config.bars(),
                midi.bpm(), notes.size(), config.loops(), device, config.channelFor(track) + 1);

        int channel = config.channelFor(track);
        for (SchedulerKind kind : SchedulerKind.select(config.scheduler())) {
            try (MidiPlayer player = MidiPlayer.openDevice(device, channel, track.program().orElse(0), kind)) {
                System.out.println(player.play(notes, midi.bpm(), patternLengthBeats, config.loops()));
            }
        }
    }
}
