package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.Voice;
import pl.livecoding.musicjam.synth.PitchSynth;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Renders notes block by block - drums, and a melody through a synth when one is given - with every
 * hit placed on its exact sample frame - into memory
 * for the tests, or into the sound card. Both use the same renderer, so the timing the tests check
 * is the timing you hear.
 */
public final class AudioEngine {
    public static final int DEFAULT_SAMPLE_RATE = 44_100;
    public static final int DEFAULT_BLOCK_SIZE = 512;
    public static final int DEFAULT_MAX_VOICES = 32;

    private static final int CHANNELS = 2;
    // how many blocks the sound card line holds: its buffer is part of how late the drums are heard
    private static final int LINE_BUFFER_BLOCKS = 4;

    private final SampleBank samples;
    private final int sampleRate;
    private final int blockSize;
    private final int maxVoices;
    // the line play() writes to, so that heardNanos() can tell another thread how far it has got
    private volatile SourceDataLine playing;
    private volatile long heardFrames;
    private volatile long stallNanos;

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

    public RenderedAudio render(List<Note> notes, double bpm, double patternLengthBeats, int loops) {
        return render(notes, bpm, patternLengthBeats, loops, null);
    }

    /** Drums and a melody together: every melody note is rendered by {@code synth}, in the same blocks. */
    public RenderedAudio render(List<Note> notes, double bpm, double patternLengthBeats, int loops, PitchSynth synth) {
        RenderSession session = new RenderSession(notes, bpm, patternLengthBeats, loops, synth);
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

    public void play(List<Note> notes, double bpm, double patternLengthBeats, int loops)
            throws LineUnavailableException {
        play(notes, bpm, patternLengthBeats, loops, null);
    }

    /**
     * Drums and a melody on one clock: every melody note is rendered by {@code synth} and placed on its
     * frame like a drum hit, so nothing goes out over MIDI and there is no latency to make up for.
     */
    public void play(List<Note> notes, double bpm, double patternLengthBeats, int loops, PitchSynth synth)
            throws LineUnavailableException {
        RenderSession session = new RenderSession(notes, bpm, patternLengthBeats, loops, synth);
        float[] mix = new float[blockSize * CHANNELS];
        byte[] pcm = new byte[blockSize * CHANNELS * 2];
        AudioFormat format = new AudioFormat(sampleRate, 16, CHANNELS, true, false);

        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, blockSize * CHANNELS * 2 * LINE_BUFFER_BLOCKS);
            line.start();
            playing = line;
            try {
                while (session.hasMore()) {
                    stallIfAsked();
                    int frames = session.renderNext(mix);
                    writeFully(line, pcm, encodePcm16(mix, frames, pcm));
                }
                line.drain();
            } finally {
                heardFrames = line.getLongFramePosition();
                playing = null;
            }
        }
    }

    /**
     * One sample, straight to the sound card: no notes and no schedule, so it plays even before step
     * 5's exercise is done.
     */
    public void play(Sample sample) throws LineUnavailableException {
        int frames = sample.frameCount();
        float[] mix = new float[frames * CHANNELS];
        for (int frame = 0; frame < frames; frame++) {
            mix[frame * CHANNELS] = sample.valueAt(frame);
            mix[frame * CHANNELS + 1] = sample.valueAt(frame);
        }
        byte[] pcm = new byte[mix.length * 2];
        AudioFormat format = new AudioFormat(sampleRate, 16, CHANNELS, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            writeFully(line, pcm, encodePcm16(mix, frames, pcm));
            line.drain();
        }
    }

    /**
     * The drum on every beat, until {@code keepGoing} says stop - for finding the synth's latency by
     * ear against it (step5.CalibrateLatency). It plays through the same kind of line as the drums in
     * {@link #play}, so the latency found holds there too; and it needs no schedule, so it plays even
     * before step 5's exercise is done. {@link #heardNanos} follows it while it plays.
     */
    public void playEveryBeat(Drum drum, double bpm, BooleanSupplier keepGoing) throws LineUnavailableException {
        if (bpm <= 0.0) {
            throw new IllegalArgumentException("BPM must be positive");
        }
        Sample click = samples.sample(drum);
        double framesPerBeat = sampleRate * 60.0 / bpm;
        float[] mix = new float[blockSize * CHANNELS];
        byte[] pcm = new byte[blockSize * CHANNELS * 2];
        AudioFormat format = new AudioFormat(sampleRate, 16, CHANNELS, true, false);

        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, blockSize * CHANNELS * 2 * LINE_BUFFER_BLOCKS);
            line.start();
            playing = line;
            try {
                for (long position = 0; keepGoing.getAsBoolean(); position += blockSize) {
                    mixEveryBeat(click, framesPerBeat, position, mix, blockSize);
                    writeFully(line, pcm, encodePcm16(mix, blockSize, pcm));
                }
                line.flush();
            } finally {
                // it never ends on its own, so there is no end to remember: the next playback starts at 0
                heardFrames = 0;
                playing = null;
            }
        }
    }

    /**
     * One block of {@link #playEveryBeat}: the click starting on the frame of every beat, counted from
     * the start, and the tail of a click that started in an earlier block.
     */
    static void mixEveryBeat(Sample click, double framesPerBeat, long blockStart, float[] mix, int frames) {
        Arrays.fill(mix, 0.0f);
        long blockEnd = blockStart + frames;
        long beat = Math.max(0L, (long) Math.floor((blockStart - click.frameCount()) / framesPerBeat));
        for (long start = Math.round(beat * framesPerBeat); start < blockEnd;
             beat++, start = Math.round(beat * framesPerBeat)) {
            long to = Math.min(start + click.frameCount(), blockEnd);
            for (long frame = Math.max(start, blockStart); frame < to; frame++) {
                float value = click.valueAt((int) (frame - start));
                int outputFrame = (int) (frame - blockStart);
                mix[outputFrame * CHANNELS] += value;
                mix[outputFrame * CHANNELS + 1] += value;
            }
        }
    }

    /**
     * How much of what {@link #play} is playing has been heard so far, in nanoseconds of audio: the
     * clock for a MIDI player that has to keep in time with it. 0 until playback starts.
     */
    public long heardNanos() {
        SourceDataLine line = playing;
        long frames = line != null ? line.getLongFramePosition() : heardFrames;
        return frames * 1_000_000_000L / sampleRate;
    }

    /**
     * Makes {@link #play} stop feeding the sound card for a while, the way a garbage collection
     * pause or a busy machine would. What the card had buffered runs out, and from then on the
     * drums are that much behind the wall clock - which a player keeping time by the wall clock has
     * no way of knowing.
     */
    public void stall(long millis) {
        stallNanos = TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private void stallIfAsked() {
        long nanos = stallNanos;
        if (nanos > 0) {
            stallNanos = 0;
            LockSupport.parkNanos(nanos);
        }
    }

    /**
     * Every note of every loop as a hit on the frame of its beat: step 3's schedule again, counted in
     * frames of audio instead of nanoseconds to wait for. Nobody waits for these frames - the renderer
     * puts each hit on its own.
     */
    static List<Hit> schedule(List<Note> notes, double bpm, double patternLengthBeats, int loops, int sampleRate) {
        if (loops <= 0) {
            throw new IllegalArgumentException("Loops must be positive");
        }
        Transport transport = new Transport(bpm, sampleRate);
        var hits = new ArrayList<Hit>(notes.size() * loops);
        for (int loop = 0; loop < loops; loop++) {
            double loopStart = loop * patternLengthBeats;
            for (Note note : notes) {
                long frame = transport.frameAtBeat(loopStart + note.beat());
                hits.add(new Hit(frame, note));
            }
        }
        return hits;
    }

    /** One note, due on a frame counted from the start of playback: a drum, or a note of the melody. */
    record Hit(long frame, Note note) {
    }

    private record SynthKey(int midiNote, int frameCount) {
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

    private final class RenderSession {
        private final Hit[] hits;
        private final long totalFrames;
        private final VoiceSlot[] voices;
        private final Transport transport;
        private final PitchSynth synth;
        private final Map<SynthKey, Sample> synthCache = new HashMap<>();
        private int nextHit;
        private long position;

        private RenderSession(List<Note> notes, double bpm, double patternLengthBeats, int loops, PitchSynth synth) {
            if (patternLengthBeats <= 0.0) {
                throw new IllegalArgumentException("Pattern length must be positive");
            }
            for (Note note : notes) {
                if (note.voice() instanceof Voice.Pitch pitch && synth == null) {
                    throw new IllegalArgumentException("The melody's note " + pitch.midiNote()
                            + " needs a synth to be rendered with - or goes out over MIDI");
                }
            }
            this.transport = new Transport(bpm, sampleRate);
            this.synth = synth;
            this.hits = schedule(notes, bpm, patternLengthBeats, loops, sampleRate).stream()
                    .sorted(Comparator.comparingLong(Hit::frame))
                    .toArray(Hit[]::new);
            this.totalFrames = transport.frameAtBeat(loops * patternLengthBeats);
            this.voices = new VoiceSlot[maxVoices];
            for (int i = 0; i < voices.length; i++) {
                voices[i] = new VoiceSlot();
            }
        }

        private boolean hasMore() {
            return position < totalFrames;
        }

        /**
         * A drum is its sample. A melody note is rendered by the synth for as long as the note lasts,
         * once for every pitch and length, and from then on it is a sample like any drum.
         */
        private Sample sampleFor(Note note) {
            return switch (note.voice()) {
                case Drum drum -> samples.sample(drum);
                case Voice.Pitch pitch -> {
                    int frameCount = (int) transport.frameAtBeat(note.durationBeats());
                    yield synthCache.computeIfAbsent(new SynthKey(pitch.midiNote(), frameCount),
                            key -> synth.render(key.midiNote(), key.frameCount(), sampleRate));
                }
            };
        }

        /**
         * Renders the next block into {@code mix} and returns how many frames it holds. A block is
         * cut at the frame of every hit that falls inside it, so each hit starts on its own frame
         * rather than on the edge of the block.
         */
        private int renderNext(float[] mix) {
            Arrays.fill(mix, 0.0f);
            int frames = (int) Math.min(blockSize, totalFrames - position);
            long blockEnd = position + frames;
            long segmentStart = position;

            while (nextHit < hits.length && hits[nextHit].frame() < blockEnd) {
                long eventFrame = hits[nextHit].frame();
                renderVoices(voices, mix, position, segmentStart, eventFrame);
                do {
                    Hit hit = hits[nextHit];
                    allocateVoice(voices, eventFrame).trigger(sampleFor(hit.note()), eventFrame, hit.note().velocity());
                    nextHit++;
                } while (nextHit < hits.length && hits[nextHit].frame() == eventFrame);
                segmentStart = eventFrame;
            }
            renderVoices(voices, mix, position, segmentStart, blockEnd);
            position = blockEnd;
            return frames;
        }
    }

    private static void renderVoices(VoiceSlot[] voices, float[] mix, long blockStart, long fromFrame, long toFrame) {
        for (VoiceSlot voice : voices) {
            if (voice.active) {
                voice.render(mix, blockStart, fromFrame, toFrame);
            }
        }
    }

    /** A free voice, or else the one closest to its end: with too many hits at once, a tail goes first. */
    private static VoiceSlot allocateVoice(VoiceSlot[] voices, long atFrame) {
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
