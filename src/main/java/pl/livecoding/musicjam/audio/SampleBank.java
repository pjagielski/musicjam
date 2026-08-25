package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Drum;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class SampleBank {
    private final EnumMap<Drum, Sample> samples;

    private SampleBank(Map<Drum, Sample> samples) {
        this.samples = new EnumMap<>(Drum.class);
        this.samples.putAll(samples);
    }

    public static SampleBank from(Map<Drum, Sample> samples) {
        Objects.requireNonNull(samples, "samples");
        return new SampleBank(samples);
    }

    public static SampleBank load(Path directory, int sampleRate)
            throws IOException, UnsupportedAudioFileException {
        Objects.requireNonNull(directory, "directory");
        var loaded = new EnumMap<Drum, Sample>(Drum.class);
        for (Drum drum : Drum.values()) {
            Path path = directory.resolve(drum.sampleFile());
            Sample sample = Files.isRegularFile(path)
                    ? WavSampleLoader.load(path, sampleRate)
                    : DrumSamples.create(drum, sampleRate);
            loaded.put(drum, sample);
        }
        return new SampleBank(loaded);
    }

    public Sample sample(Drum drum) {
        Sample sample = samples.get(drum);
        if (sample == null) {
            throw new IllegalArgumentException("No sample configured for " + drum);
        }
        return sample;
    }
}
