package pl.livecoding.musicjam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * Everything needed to play a looped phrase from a MIDI file via {@link BeatApp}: which file and
 * track, which bar window, how many loops, which synth for melodic notes, which drum pattern to
 * pair it with, and an optional external MIDI device to route to instead of native synthesis - with
 * how the melody sent there keeps in time with the drums: {@code loop} or {@code live}.
 *
 * <p>Three ways to build one: fluent ({@link #forFile}), from a {@code .properties} file
 * ({@link #fromPropertiesFile}), or from CLI args (handled internally by {@link BeatApp}).
 */
public record PhraseRequest(
        Path file, int trackIndex, int startBar, int bars, int loops, String synth, String drums,
        String midiDevice, String midiSync) {

    public PhraseRequest {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(synth, "synth");
        Objects.requireNonNull(drums, "drums");
        if (!"loop".equals(midiSync) && !"live".equals(midiSync)) {
            throw new IllegalArgumentException("midiSync must be \"loop\" or \"live\"");
        }
        if (bars <= 0) {
            throw new IllegalArgumentException("bars must be positive");
        }
        if (loops <= 0) {
            throw new IllegalArgumentException("loops must be positive");
        }
    }

    public static Builder forFile(String file) {
        return forFile(Path.of(file));
    }

    public static Builder forFile(Path file) {
        return new Builder(file);
    }

    public static PhraseRequest fromPropertiesFile(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        String file = properties.getProperty("file");
        if (file == null) {
            throw new IllegalArgumentException("Config file " + path + " is missing \"file\"");
        }
        Builder builder = forFile(file);
        applyIfPresent(properties, "track", builder::track);
        applyIfPresent(properties, "fromBar", builder::fromBar);
        applyIfPresent(properties, "bars", builder::bars);
        applyIfPresent(properties, "loops", builder::loops);
        String synth = properties.getProperty("synth");
        if (synth != null) {
            builder.synth(synth);
        }
        String drums = properties.getProperty("drums");
        if (drums != null) {
            builder.drums(drums);
        }
        String midiDevice = properties.getProperty("midiDevice");
        if (midiDevice != null) {
            builder.midiDevice(midiDevice);
        }
        String midiSync = properties.getProperty("midiSync");
        if (midiSync != null) {
            builder.midiSync(midiSync.trim());
        }
        return builder.build();
    }

    private static void applyIfPresent(Properties properties, String key, java.util.function.IntConsumer setter) {
        String value = properties.getProperty(key);
        if (value != null) {
            setter.accept(Integer.parseInt(value.trim()));
        }
    }

    public void playJam() throws Exception {
        BeatApp.playJam(this);
    }

    public static final class Builder {
        private final Path file;
        private int trackIndex = 1;
        private int startBar = 0;
        private int bars = 2;
        private int loops = 4;
        private String synth = "anthem";
        private String drums = "shape";
        private String midiDevice;
        private String midiSync = "loop";

        private Builder(Path file) {
            this.file = file;
        }

        public Builder track(int trackIndex) {
            this.trackIndex = trackIndex;
            return this;
        }

        public Builder fromBar(int startBar) {
            this.startBar = startBar;
            return this;
        }

        public Builder bars(int bars) {
            this.bars = bars;
            return this;
        }

        public Builder loops(int loops) {
            this.loops = loops;
            return this;
        }

        public Builder synth(String synth) {
            this.synth = synth;
            return this;
        }

        public Builder drums(String drums) {
            this.drums = drums;
            return this;
        }

        public Builder midiDevice(String midiDevice) {
            this.midiDevice = midiDevice;
            return this;
        }

        public Builder midiSync(String midiSync) {
            this.midiSync = midiSync;
            return this;
        }

        public PhraseRequest build() {
            return new PhraseRequest(file, trackIndex, startBar, bars, loops, synth, drums, midiDevice, midiSync);
        }

        public void playJam() throws Exception {
            build().playJam();
        }
    }
}
