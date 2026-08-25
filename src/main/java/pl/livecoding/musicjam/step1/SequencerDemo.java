package pl.livecoding.musicjam.step1;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class SequencerDemo {
    private static final String DEFAULT_CONFIG = "src/main/resources/jam.properties";

    public static void main(String[] args) throws Exception {
        Path file = resolveFile(args);

        var sequence = MidiSystem.getSequence(file.toFile());
        try (var sequencer = MidiSystem.getSequencer()) {
            sequencer.open();
            sequencer.setSequence(sequence);
            sequencer.start();
            Thread.sleep(sequencer.getMicrosecondLength() / 1000);
            sequencer.stop();
        }
    }

    private static Path resolveFile(String[] args) throws IOException {
        if (args.length == 0) {
            return Config.fromFile(DEFAULT_CONFIG).file();
        }
        if ("--config".equals(args[0])) {
            if (args.length < 2) {
                throw new IllegalArgumentException("Usage: --config <file.properties>");
            }
            return Config.fromFile(args[1]).file();
        }
        return Path.of(args[0]);
    }

    private record Config(Path file) {
        static Config fromFile(String path) throws IOException {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(Path.of(path))) {
                properties.load(in);
            }
            String file = properties.getProperty("file");
            if (file == null) {
                throw new IllegalArgumentException("Config file " + path + " is missing \"file\"");
            }
            return new Config(Path.of(file));
        }
    }
}
