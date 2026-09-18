package pl.livecoding.musicjam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record Config(Path file) {
    private static final String DEFAULT_PATH = "src/main/resources/jam.properties";
    private static final String USAGE = """
            Usage:
              --args="<file.mid>"
              --args="--config <file.properties>"
              no arguments                        (reads %s)"""
            .formatted(DEFAULT_PATH);

    public static Config fromArgs(String[] args) throws IOException {
        Path file = null;
        Config fromFile = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> fromFile = fromFile(value(args, ++i, "--config"));
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
        return new Config(file);
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
        return new Config(Path.of(file));
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value\n" + USAGE);
        }
        return args[index];
    }
}
