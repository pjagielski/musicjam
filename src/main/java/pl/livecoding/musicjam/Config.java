package pl.livecoding.musicjam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.Properties;

public record Config(Path file, OptionalInt track) {
    private static final String DEFAULT_PATH = "src/main/resources/jam.properties";
    private static final String USAGE = """
            Usage:
              --args="<file.mid> [--track <n>]"
              --args="--config <file.properties> [--track <n>]"
              --args="--overview"                 (list the tracks instead of one track's notes)
              no arguments                        (reads %s)"""
            .formatted(DEFAULT_PATH);

    public static Config fromArgs(String[] args) throws IOException {
        Path file = null;
        OptionalInt track = OptionalInt.empty();
        boolean overview = false;
        Config fromFile = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> fromFile = fromFile(value(args, ++i, "--config"));
                case "--track" -> track = OptionalInt.of(Integer.parseInt(value(args, ++i, "--track")));
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
        if (track.isEmpty() && fromFile != null) {
            track = fromFile.track();
        }
        return new Config(file, overview ? OptionalInt.empty() : track);
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
                track == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(track.trim())));
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value\n" + USAGE);
        }
        return args[index];
    }
}
