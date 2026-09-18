package pl.livecoding.musicjam.step5;

import pl.livecoding.musicjam.Config;
import pl.livecoding.musicjam.audio.AudioEngine;
import pl.livecoding.musicjam.audio.SampleBank;
import pl.livecoding.musicjam.midi.MidiFile;
import pl.livecoding.musicjam.midi.MidiFileReader;
import pl.livecoding.musicjam.midi.MidiPlayer;
import pl.livecoding.musicjam.midi.TrackData;
import pl.livecoding.musicjam.model.DrumPatterns;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.PatternCompiler;
import pl.livecoding.musicjam.scheduler.SchedulerKind;
import pl.livecoding.musicjam.synth.Synths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class PlayJam {
    private static final int BEATS_PER_BAR = 4;
    private static final long STALL_AFTER_MILLIS = 3_000;

    static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        MidiFile midi = MidiFileReader.read(config.file());
        TrackData track = midi.track(config.track().orElseThrow(() -> new IllegalArgumentException(
                "No track to play: set track= in the properties file or pass --track <n>")));

        double patternLengthBeats = config.bars() * BEATS_PER_BAR;
        List<Note> melody = track.window(config.fromBar() * BEATS_PER_BAR, patternLengthBeats);
        List<Note> drumBar = PatternCompiler.compile(DrumPatterns.named(config.drums()), BEATS_PER_BAR);
        int drumBars = config.bars() * config.loops();

        System.out.printf(Locale.ROOT,
                "%s track %d (%s): bars %d-%d, %.1f BPM, %d loops, drums \"%s\", melody on %s%n",
                midi.file().getFileName(), track.index(), track.name(),
                config.fromBar() + 1, config.fromBar() + config.bars(), midi.bpm(), config.loops(),
                config.drums(), config.synth().map(name -> "synth \"" + name + "\", no MIDI")
                        .orElseGet(() -> config.midiDevice().map(name -> "\"" + name + "\"").orElse("Gervill")));

        AudioEngine drums = new AudioEngine(SampleBank.load(Path.of("samples"), AudioEngine.DEFAULT_SAMPLE_RATE));
        if (config.synth().isPresent()) {
            // one clock: the melody is rendered into the same blocks as the drums, so a stall pauses both
            List<Note> jam = new ArrayList<>(melody);
            for (int bar = 0; bar < config.bars(); bar++) {
                for (Note hit : drumBar) {
                    jam.add(new Note(bar * BEATS_PER_BAR + hit.beat(), hit.voice(), hit.durationBeats(), hit.velocity()));
                }
            }
            stallLaterIfAsked(drums, config.stall());
            drums.play(jam, midi.bpm(), patternLengthBeats, config.loops(), Synths.named(config.synth().get()));
            return;
        }

        int channel = config.channelFor(track);
        SchedulerKind scheduler = SchedulerKind.select(config.scheduler()).getFirst();
        try (MidiPlayer player = config.midiDevice().isPresent()
                ? MidiPlayer.openDevice(config.midiDevice().get(), channel, track.program().orElse(0), scheduler)
                : MidiPlayer.openMidi(channel, track.program().orElse(0), scheduler)) {
            AtomicReference<Exception> failure = new AtomicReference<>();
            Thread drumThread = Thread.ofPlatform().start(() -> {
                try {
                    drums.play(drumBar, midi.bpm(), BEATS_PER_BAR, drumBars);
                } catch (Exception exception) {
                    failure.set(exception);
                }
            });
            // the first loop is timed from the drums too, so wait until they can be heard
            while (drums.heardNanos() == 0 && drumThread.isAlive()) {
                Thread.sleep(1);
            }
            stallLaterIfAsked(drums, config.stall());

            player.playLoop(melody, midi.bpm(), patternLengthBeats, config.loops(),
                    drums::heardNanos, TimeUnit.MILLISECONDS.toNanos(config.midiLatency()));

            drumThread.join();
            if (failure.get() != null) {
                throw failure.get();
            }
        }
    }

    private static void stallLaterIfAsked(AudioEngine drums, int millis) {
        if (millis > 0) {
            Thread.ofVirtual().start(() -> stallLater(drums, millis));
        }
    }

    private static void stallLater(AudioEngine drums, int millis) {
        try {
            Thread.sleep(STALL_AFTER_MILLIS);
        } catch (InterruptedException interrupted) {
            return;
        }
        System.out.printf("the drums stall for %d ms%n", millis);
        drums.stall(millis);
    }
}
