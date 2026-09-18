package pl.livecoding.musicjam;

import pl.livecoding.musicjam.midi.TrackData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;

public record Config(
        Path file, OptionalInt track, int fromBar, int bars, int loops, String scheduler,
        Optional<String> midiDevice, OptionalInt midiChannel) {
    private static final String DEFAULT_PATH = "src/main/resources/jam.properties";
    private static final int DEFAULT_FROM_BAR = 0;
    private static final int DEFAULT_BARS = 2;
    private static final int DEFAULT_LOOPS = 4;
    private static final String DEFAULT_SCHEDULER = "platform";
    private static final String USAGE = """
            Usage:
              --args="<file.mid> [--track <n>] [--fromBar <n>] [--bars <n>] [--loops <n>]"
              --args="--scheduler platform|virtual|pool|scoped|all"
              --args="--midiDevice <part of its name>"  (play on a device listMidiDevices shows)
              --args="--midiChannel <1-16>"       (send on this channel instead of the track's own)
              --args="--config <file.properties> [--track <n>]"
              --args="--overview"                 (list the tracks instead of one track's notes)
              no arguments                        (reads %s)"""
            .formatted(DEFAULT_PATH);

    public Config {
        if (midiChannel.isPresent() && (midiChannel.getAsInt() < 1 || midiChannel.getAsInt() > 16)) {
            throw new IllegalArgumentException(
                    "midiChannel counts from 1 to 16, the way a synth does, not " + midiChannel.getAsInt());
        }
    }

    /**
     * The channel to send the track's notes on, counted from 0 the way javax.sound.midi counts: the
     * one midiChannel= names - counted from 1, the way a synth's display does - or else the track's own.
     */
    public int channelFor(TrackData track) {
        return midiChannel.isPresent() ? midiChannel.getAsInt() - 1 : track.channel().orElse(0);
    }

    public static Config fromArgs(String[] args) throws IOException {
        Path file = null;
        OptionalInt track = OptionalInt.empty();
        OptionalInt fromBar = OptionalInt.empty();
        OptionalInt bars = OptionalInt.empty();
        OptionalInt loops = OptionalInt.empty();
        String scheduler = null;
        String midiDevice = null;
        OptionalInt midiChannel = OptionalInt.empty();
        boolean overview = false;
        Config fromFile = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> fromFile = fromFile(value(args, ++i, "--config"));
                case "--track" -> track = OptionalInt.of(number(args, ++i, "--track"));
                case "--fromBar" -> fromBar = OptionalInt.of(number(args, ++i, "--fromBar"));
                case "--bars" -> bars = OptionalInt.of(number(args, ++i, "--bars"));
                case "--loops" -> loops = OptionalInt.of(number(args, ++i, "--loops"));
                case "--scheduler" -> scheduler = value(args, ++i, "--scheduler");
                case "--midiDevice" -> midiDevice = value(args, ++i, "--midiDevice");
                case "--midiChannel" -> midiChannel = OptionalInt.of(number(args, ++i, "--midiChannel"));
                case "--overview" -> overview = true;
                default -> {
                    if (args[i].startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option " + args[i] + "\n" + USAGE);
                    }
                    file = Path.of(args[i]);
                }
            }
        }

        if (file == null && fromFile == null) {
            fromFile = fromFile(DEFAULT_PATH);
        }
        if (file == null) {
            file = fromFile.file();
        }
        if (fromFile != null) {
            if (track.isEmpty()) {
                track = fromFile.track();
            }
            if (fromBar.isEmpty()) {
                fromBar = OptionalInt.of(fromFile.fromBar());
            }
            if (bars.isEmpty()) {
                bars = OptionalInt.of(fromFile.bars());
            }
            if (loops.isEmpty()) {
                loops = OptionalInt.of(fromFile.loops());
            }
            if (scheduler == null) {
                scheduler = fromFile.scheduler();
            }
            if (midiDevice == null) {
                midiDevice = fromFile.midiDevice().orElse(null);
            }
            if (midiChannel.isEmpty()) {
                midiChannel = fromFile.midiChannel();
            }
        }

        return new Config(
                file,
                overview ? OptionalInt.empty() : track,
                fromBar.orElse(DEFAULT_FROM_BAR),
                bars.orElse(DEFAULT_BARS),
                loops.orElse(DEFAULT_LOOPS),
                scheduler == null ? DEFAULT_SCHEDULER : scheduler,
                Optional.ofNullable(midiDevice),
                midiChannel);
    }

    private static Config fromFile(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            properties.load(in);
        }
        String file = properties.getProperty("file");
        if (file == null) {
            throw new IllegalArgumentException("Config file " + path + " is missing \"file\"");
        }
        String track = properties.getProperty("track");
        return new Config(
                Path.of(file),
                track == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(track.trim())),
                intProperty(properties, "fromBar", DEFAULT_FROM_BAR),
                intProperty(properties, "bars", DEFAULT_BARS),
                intProperty(properties, "loops", DEFAULT_LOOPS),
                properties.getProperty("scheduler", DEFAULT_SCHEDULER).trim(),
                Optional.ofNullable(properties.getProperty("midiDevice")).map(String::trim),
                optionalIntProperty(properties, "midiChannel"));
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }

    private static OptionalInt optionalIntProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(value.trim()));
    }

    private static int number(String[] args, int index, String option) {
        return Integer.parseInt(value(args, index, option));
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value\n" + USAGE);
        }
        return args[index];
    }
}
