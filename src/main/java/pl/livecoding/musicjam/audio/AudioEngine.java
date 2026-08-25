package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.PatternCompiler;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Voice;
import pl.livecoding.musicjam.synth.AnthemLeadSynth;
import pl.livecoding.musicjam.synth.PitchSynth;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AudioEngine {
    public static final int DEFAULT_SAMPLE_RATE = 44_100;
    public static final int DEFAULT_BLOCK_SIZE = 512;
    public static final int DEFAULT_MAX_VOICES = 32;

    private static final int CHANNELS = 2;
    private static final PitchSynth DEFAULT_SYNTH = new AnthemLeadSynth()::render;

    private final SampleBank samples;
    private final int sampleRate;
    private final int blockSize;
    private final int maxVoices;

    public AudioEngine(SampleBank samples) {
        this(samples, DEFAULT_SAMPLE_RATE, DEFAULT_BLOCK_SIZE, DEFAULT_MAX_VOICES);
    }

    public AudioEngine(SampleBank samples, int sampleRate, int blockSize, int maxVoices) {
        if (sampleRate <= 0 || blockSize <= 0 || maxVoices <= 0) {
            throw new IllegalArgumentException("Audio configuration values must be positive");
        }
        this.samples = samples;
        this.sampleRate = sampleRate;
        this.blockSize = blockSize;
        this.maxVoices = maxVoices;
    }

    public RenderedAudio render(Song song, int loops) {
        RenderSession session = session(PatternCompiler.compile(song), song.bpm(), PatternCompiler.totalBeats(song), loops);
        if (session.totalFrames > Integer.MAX_VALUE / CHANNELS) {
            throw new IllegalArgumentException("Render is too long to keep in memory");
        }

        float[] rendered = new float[(int) session.totalFrames * CHANNELS];
        float[] block = new float[blockSize * CHANNELS];
        int destination = 0;
        while (session.hasMore()) {
            int frames = session.renderNext(block);
            int sampleCount = frames * CHANNELS;
            System.arraycopy(block, 0, rendered, destination, sampleCount);
            destination += sampleCount;
        }
        return new RenderedAudio(sampleRate, rendered);
    }

    public void writeWav(Song song, int loops, Path destination) throws IOException {
        RenderSession session = session(PatternCompiler.compile(song), song.bpm(), PatternCompiler.totalBeats(song), loops);
        float[] mix = new float[blockSize * CHANNELS];
        byte[] pcm = new byte[blockSize * CHANNELS * 2];
        try (var output = WavFileOutput.open(destination, sampleRate, session.totalFrames)) {
            while (session.hasMore()) {
                int frames = session.renderNext(mix);
                int bytes = encodePcm16(mix, frames, pcm);
                output.write(pcm, bytes);
            }
        }
    }

    public void play(Song song, int loops) throws LineUnavailableException {
        play(song, loops, DEFAULT_SYNTH);
    }

    public void play(Song song, int loops, PitchSynth synth) throws LineUnavailableException {
        play(PatternCompiler.compile(song), song.bpm(), PatternCompiler.totalBeats(song), loops, synth);
    }

    public void play(List<Note> notes, double bpm, double patternLengthBeats, int loops)
            throws LineUnavailableException {
        play(notes, bpm, patternLengthBeats, loops, DEFAULT_SYNTH);
    }

    public void play(List<Note> notes, double bpm, double patternLengthBeats, int loops, PitchSynth synth)
            throws LineUnavailableException {
        RenderSession session = session(notes, bpm, patternLengthBeats, loops, synth);
        float[] mix = new float[blockSize * CHANNELS];
        byte[] pcm = new byte[blockSize * CHANNELS * 2];
        AudioFormat format = new AudioFormat(sampleRate, 16, CHANNELS, true, false);

        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, blockSize * CHANNELS * 2 * 4);
            line.start();
            while (session.hasMore()) {
                int frames = session.renderNext(mix);
                int bytes = encodePcm16(mix, frames, pcm);
                writeFully(line, pcm, bytes);
            }
            line.drain();
        }
    }

    private static int encodePcm16(float[] mix, int frames, byte[] pcm) {
        int samplesToEncode = frames * CHANNELS;
        for (int i = 0; i < samplesToEncode; i++) {
            float clipped = Math.max(-1.0f, Math.min(1.0f, mix[i]));
            int value = Math.round(clipped * 32767.0f);
            pcm[i * 2] = (byte) (value & 0xff);
            pcm[i * 2 + 1] = (byte) ((value >>> 8) & 0xff);
        }
        return samplesToEncode * 2;
    }

    private static void writeFully(SourceDataLine line, byte[] pcm, int bytes) {
        int written = 0;
        while (written < bytes) {
            int count = line.write(pcm, written, bytes - written);
            if (count <= 0) {
                throw new IllegalStateException("Audio line stopped while writing");
            }
            written += count;
        }
    }

    private RenderSession session(List<Note> notes, double bpm, double patternLengthBeats, int loops) {
        return session(notes, bpm, patternLengthBeats, loops, DEFAULT_SYNTH);
    }

    private RenderSession session(
            List<Note> notes, double bpm, double patternLengthBeats, int loops, PitchSynth synth) {
        if (loops <= 0) {
            throw new IllegalArgumentException("Loops must be positive");
        }
        if (patternLengthBeats <= 0.0) {
            throw new IllegalArgumentException("Pattern length must be positive");
        }
        return new RenderSession(notes, bpm, patternLengthBeats, loops, synth);
    }

    private final class RenderSession {
        private final BarHit[] barHits;
        private final VoiceSlot[] voices;
        private final Transport transport;
        private final PitchSynth synth;
        private final Map<SynthKey, Sample> synthCache = new HashMap<>();
        private final double patternLengthBeats;
        private final int loops;
        private final long totalFrames;
        private int nextLoop;
        private int nextHit;
        private long position;

        private RenderSession(
                List<Note> notes, double bpm, double patternLengthBeats, int loops, PitchSynth synth) {
            this.transport = new Transport(bpm, sampleRate);
            this.synth = synth;
            this.patternLengthBeats = patternLengthBeats;
            this.loops = loops;
            this.barHits = notes.stream()
                    .map(note -> new BarHit(note.beat(), sampleFor(note.voice(), note.durationBeats()), note.velocity()))
                    .toArray(BarHit[]::new);
            totalFrames = transport.frameAtBeat(loops * patternLengthBeats);
            voices = new VoiceSlot[maxVoices];
            for (int i = 0; i < voices.length; i++) {
                voices[i] = new VoiceSlot();
            }
        }

        private Sample sampleFor(Voice voice, double durationBeats) {
            return switch (voice) {
                case Drum drum -> samples.sample(drum);
                case Voice.Pitch pitch -> {
                    int frameCount = (int) transport.frameAtBeat(durationBeats);
                    yield synthCache.computeIfAbsent(new SynthKey(pitch.midiNote(), frameCount),
                            key -> synth.render(key.midiNote(), key.frameCount(), sampleRate));
                }
            };
        }

        private boolean hasMore() {
            return position < totalFrames;
        }

        private int renderNext(float[] mix) {
            Arrays.fill(mix, 0.0f);
            int frames = (int) Math.min(blockSize, totalFrames - position);
            long blockEnd = position + frames;
            long segmentStart = position;

            while (hasNextEvent() && nextEventFrame() < blockEnd) {
                long eventFrame = nextEventFrame();
                renderVoices(mix, position, segmentStart, eventFrame);
                do {
                    BarHit hit = barHits[nextHit];
                    allocateVoice(eventFrame).trigger(hit.sample, eventFrame, hit.velocity);
                    advanceEvent();
                } while (hasNextEvent() && nextEventFrame() == eventFrame);
                segmentStart = eventFrame;
            }
            renderVoices(mix, position, segmentStart, blockEnd);
            position = blockEnd;
            return frames;
        }

        private void renderVoices(float[] mix, long blockStart, long fromFrame, long toFrame) {
            for (VoiceSlot voice : voices) {
                if (voice.active) {
                    voice.render(mix, blockStart, fromFrame, toFrame);
                }
            }
        }

        private boolean hasNextEvent() {
            return barHits.length > 0 && nextLoop < loops;
        }

        private long nextEventFrame() {
            double absoluteBeat = nextLoop * patternLengthBeats + barHits[nextHit].beat;
            return transport.frameAtBeat(absoluteBeat);
        }

        private void advanceEvent() {
            nextHit++;
            if (nextHit == barHits.length) {
                nextHit = 0;
                nextLoop++;
            }
        }

        private VoiceSlot allocateVoice(long atFrame) {
            VoiceSlot shortest = voices[0];
            for (VoiceSlot voice : voices) {
                if (!voice.active) {
                    return voice;
                }
                if (voice.remainingFrames(atFrame) < shortest.remainingFrames(atFrame)) {
                    shortest = voice;
                }
            }
            return shortest;
        }
    }

    private record BarHit(double beat, Sample sample, float velocity) {
    }

    private record SynthKey(int midiNote, int frameCount) {
    }

    private static final class VoiceSlot {
        private Sample sample;
        private long startFrame;
        private float gain;
        private boolean active;

        private void trigger(Sample sample, long startFrame, float gain) {
            this.sample = sample;
            this.startFrame = startFrame;
            this.gain = gain;
            this.active = true;
        }

        private long remainingFrames(long position) {
            long played = Math.max(0L, position - startFrame);
            return sample.frameCount() - played;
        }

        private void render(float[] mix, long blockStart, long fromFrame, long toFrame) {
            long firstFrame = Math.max(fromFrame, startFrame);
            long sampleFrame = firstFrame - startFrame;
            for (long frame = firstFrame; frame < toFrame && sampleFrame < sample.frameCount();
                 frame++, sampleFrame++) {
                float value = sample.valueAt((int) sampleFrame) * gain;
                int outputFrame = (int) (frame - blockStart);
                mix[outputFrame * CHANNELS] += value;
                mix[outputFrame * CHANNELS + 1] += value;
            }
            if (sampleFrame >= sample.frameCount()) {
                active = false;
            }
        }
    }
}
