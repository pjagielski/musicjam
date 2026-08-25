package pl.livecoding.musicjam.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;

public final class WavSampleLoader {

    private WavSampleLoader() {
    }

    public static Sample load(Path path, int targetSampleRate)
            throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(path.toFile())) {
            int channels = source.getFormat().getChannels();
            long expectedFrames = expectedFrames(source, targetSampleRate);
            var targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    targetSampleRate,
                    16,
                    channels,
                    channels * 2,
                    targetSampleRate,
                    false
            );
            if (!AudioSystem.isConversionSupported(targetFormat, source.getFormat())) {
                throw new IllegalArgumentException(
                        "Cannot convert " + path + " from " + source.getFormat() + " to " + targetFormat
                );
            }

            try (AudioInputStream converted = AudioSystem.getAudioInputStream(targetFormat, source)) {
                return decodeMono(converted.readAllBytes(), channels, expectedFrames);
            }
        }
    }

    private static long expectedFrames(AudioInputStream source, int targetSampleRate) {
        long sourceFrames = source.getFrameLength();
        if (sourceFrames == AudioSystem.NOT_SPECIFIED) {
            return AudioSystem.NOT_SPECIFIED;
        }
        return Math.round(sourceFrames * targetSampleRate / source.getFormat().getSampleRate());
    }

    private static Sample decodeMono(byte[] pcm, int channels, long expectedFrames) {
        int frameSize = channels * 2;
        int decodedFrames = pcm.length / frameSize;
        int frames = decodedFrames;
        if (expectedFrames != AudioSystem.NOT_SPECIFIED) {
            frames = (int) Math.min(frames, expectedFrames);
        }
        int leadingPadding = decodedFrames - frames;
        float[] mono = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            int frameOffset = (frame + leadingPadding) * frameSize;
            float sum = 0.0f;
            for (int channel = 0; channel < channels; channel++) {
                int offset = frameOffset + channel * 2;
                int value = (pcm[offset] & 0xff) | (pcm[offset + 1] << 8);
                sum += (short) value / 32768.0f;
            }
            mono[frame] = sum / channels;
        }
        return Sample.mono(mono);
    }
}
