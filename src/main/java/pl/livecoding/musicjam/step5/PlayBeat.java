package pl.livecoding.musicjam.step5;

import pl.livecoding.musicjam.Config;
import pl.livecoding.musicjam.audio.AudioEngine;
import pl.livecoding.musicjam.audio.SampleBank;
import pl.livecoding.musicjam.midi.MidiFileReader;
import pl.livecoding.musicjam.model.DrumPatterns;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.PatternCompiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

class PlayBeat {
    private static final int BEATS_PER_BAR = 4;

    static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        double bpm = MidiFileReader.read(config.file()).bpm();
        List<Note> bar = PatternCompiler.compile(DrumPatterns.named(config.drums()), BEATS_PER_BAR);
        int bars = config.bars() * config.loops();
        AudioEngine engine = new AudioEngine(
                SampleBank.load(Path.of("samples"), AudioEngine.DEFAULT_SAMPLE_RATE),
                AudioEngine.DEFAULT_SAMPLE_RATE, config.block(), AudioEngine.DEFAULT_MAX_VOICES);

        System.out.printf(Locale.ROOT, "drums \"%s\": %d hits a bar, %d bars at %.1f BPM, blocks of %d frames%n",
                config.drums(), bar.size(), bars, bpm, config.block());
        engine.play(bar, bpm, BEATS_PER_BAR, bars);
    }
}
