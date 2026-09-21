package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Envelope;
import pl.livecoding.musicjam.model.Note;
import pl.livecoding.musicjam.model.PatternCompiler;
import pl.livecoding.musicjam.model.Song;
import pl.livecoding.musicjam.model.Voice;
import pl.livecoding.musicjam.synth.AnthemLeadSynth;
import pl.livecoding.musicjam.synth.LivePitchSynth;
import pl.livecoding.musicjam.synth.PitchSynth;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AudioEngine {
    public static final int DEFAULT_SAMPLE_RATE = 44_100;
    public static final int DEFAULT_BLOCK_SIZE = 512;
    public static final int DEFAULT_MAX_VOICES = 32;
    // how far back a stutter can reach for its slice: more than the longest slice at the slowest tempo
    private static final int STUTTER_MEMORY_SECONDS = 4;

    private static final int CHANNELS = 2;
    private static final PitchSynth DEFAULT_SYNTH = new AnthemLeadSynth()::render;

    private final SampleBank samples;
    private final int sampleRate;
    private final int blockSize;
    private final int maxVoices;
    // the line play() is writing to, so that heardNanos() can tell another thread how far it has got
    private volatile SourceDataLine playing;
    private volatile long heardFrames;

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
            playing = line;
            try {
                while (session.hasMore()) {
                    int frames = session.renderNext(mix);
                    int bytes = encodePcm16(mix, frames, pcm);
                    writeFully(line, pcm, bytes);
                }
                line.drain();
            } finally {
                heardFrames = line.getLongFramePosition();
                playing = null;
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
     * Plays a jam that can change while it plays. Each loop is compiled from whatever {@code jam}
     * returns just before that loop starts - song and synth alike - so edits land on the next loop
     * boundary, with the same sample accuracy as {@link #play}. When {@code externalMelody} is not
     * null, the melody's notes go there instead of through the synth, each one sent when the audio
     * device reaches its frame, less {@link LiveSession#setExternalLatencyMillis the synth's latency},
     * so an external synth stays in time with the drums.
     */
    public LiveSession playLive(Supplier<Jam> jam, NoteListener externalMelody)
            throws LineUnavailableException {
        return new LiveSession(jam, externalMelody);
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
        private final Map<ShapeKey, Sample> shapeCache = new HashMap<>();
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
                    .map(note -> new BarHit(note.beat(), sampleFor(note), note.velocity()))
                    .toArray(BarHit[]::new);
            totalFrames = transport.frameAtBeat(loops * patternLengthBeats);
            voices = new VoiceSlot[maxVoices];
            for (int i = 0; i < voices.length; i++) {
                voices[i] = new VoiceSlot();
            }
        }

        private Sample sampleFor(Note note) {
            return switch (note.voice()) {
                case Drum drum -> drumSample(drum, note, transport, shapeCache);
                case Voice.Pitch pitch -> {
                    int frameCount = (int) transport.frameAtBeat(note.durationBeats());
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
                renderVoices(voices, mix, mix, position, segmentStart, eventFrame);
                do {
                    BarHit hit = barHits[nextHit];
                    allocateVoice(voices, eventFrame).trigger(hit.sample, eventFrame, hit.velocity);
                    advanceEvent();
                } while (hasNextEvent() && nextEventFrame() == eventFrame);
                segmentStart = eventFrame;
            }
            renderVoices(voices, mix, mix, position, segmentStart, blockEnd);
            position = blockEnd;
            return frames;
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

    }

    /** Live voices go to the synth's own bus, so effects can be put across them alone. */
    private static void renderVoices(VoiceSlot[] voices, float[] mix, float[] bus,
                                     long blockStart, long fromFrame, long toFrame) {
        for (VoiceSlot voice : voices) {
            if (voice.active) {
                voice.render(voice.isLive() ? bus : mix, blockStart, fromFrame, toFrame);
            }
        }
    }

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

    LiveRenderer liveRenderer(Supplier<Jam> jams, Consumer<ExternalNote> externalMelody) {
        return new LiveRenderer(jams, externalMelody);
    }

    /** What is audible right now: the song of the loop being heard, and how far into that loop. */
    public record Position(Song song, double beat, double lengthBeats) {
    }

    /** One loop's worth of what to play live: the song, and the synth its melody is rendered with. */
    public record Jam(Song song, PitchSynth synth) {
    }

    private record LiveSynthKey(PitchSynth synth, int midiNote, int frameCount) {
    }

    record ExternalNote(long frame, boolean on, int midiNote, int velocity) {
    }

    /**
     * A hit waiting for its frame: a rendered sample, or a note for a live synth, whose voice is made
     * when the hit is played. Keeping the recipe rather than the voice is what lets a stutter play
     * the same note again, through the synth as its knobs are now.
     */
    private record LiveHit(long frame, Sample sample, LivePitchSynth synth, int midiNote, int heldFrames,
                           float gain, boolean kick) {

        static LiveHit sample(long frame, Sample sample, float gain, boolean kick) {
            return new LiveHit(frame, sample, null, 0, 0, gain, kick);
        }

        static LiveHit note(long frame, LivePitchSynth synth, int midiNote, int heldFrames, float gain) {
            return new LiveHit(frame, null, synth, midiNote, heldFrames, gain, false);
        }

        /** The same hit at {@code at}, held no longer than {@code room}: a stutter chops, it does not smear. */
        LiveHit repeatedAt(long at, long room) {
            return new LiveHit(at, sample, synth, midiNote, (int) Math.min(heldFrames, room), gain, kick);
        }
    }

    private record LoopMark(long startFrame, Song song, double lengthBeats) {
    }

    private record Loops(LoopMark previous, LoopMark current) {
    }

    /**
     * Renders an endless song block by block. A loop is compiled from whatever the supplier returns
     * when the block about to be rendered reaches that loop's first frame; within the loop every hit
     * is placed at its exact frame, as in {@link RenderSession}. The render position never resets,
     * so a loop at a new tempo starts exactly where the previous one ended.
     */
    final class LiveRenderer {
        private final Supplier<Jam> jams;
        private final Consumer<ExternalNote> externalMelody;
        private final Map<LiveSynthKey, Sample> synthCache = new HashMap<>();
        private final Map<ShapeKey, Sample> shapeCache = new HashMap<>();
        private final VoiceSlot[] voices = new VoiceSlot[maxVoices];
        private final ArrayDeque<LiveHit> hits = new ArrayDeque<>();
        // the synth channel, kept apart from the drums so its effects only colour the synth
        private final float[] bus = new float[blockSize * CHANNELS];
        private final float[] stereo = new float[CHANNELS];
        // where in this block a kick landed: the sidechain's key, played to the effects in time
        private final int[] kickOffsets = new int[blockSize];
        private int kickCount;
        // the song's hits of the last few seconds, played or not: where a stutter's slice comes from
        private final ArrayDeque<LiveHit> recent = new ArrayDeque<>();
        // a stutter's repeats of its slice, due at their frames alongside the song's own hits
        private final ArrayDeque<LiveHit> repeats = new ArrayDeque<>();
        // the slice's hits, kept from its first repeat on: the song's memory moves on, the slice must not
        private final List<LiveHit> slice = new ArrayList<>();
        private volatile double stutterRequested;
        private double stutterBeats;
        private long sliceStart;
        private long sliceFrames;
        private long nextRepeat;
        private AudioEffect effects;
        private volatile Loops loops = new Loops(null, null);
        private volatile boolean scheduling = true;
        private long position;
        private long nextLoopStart;

        private LiveRenderer(Supplier<Jam> jams, Consumer<ExternalNote> externalMelody) {
            this.jams = jams;
            this.externalMelody = externalMelody;
            for (int i = 0; i < voices.length; i++) {
                voices[i] = new VoiceSlot();
            }
        }

        /**
         * Repeats the slice of the grid, {@code beats} long, that the next block falls in — drums and
         * synth notes alike — until called with 0. Safe from any thread.
         */
        void stutter(double beats) {
            stutterRequested = Math.max(0, beats);
        }

        /**
         * No more loops and no more notes: what is already sounding plays out, and the effects keep
         * ringing. This is what a Stop that lets the delay finish its repeats is made of. A stutter
         * stops with it, or its repeats would never let the tail end.
         */
        void stopScheduling() {
            scheduling = false;
            stutterRequested = 0;
            hits.clear();
        }

        void renderNext(float[] mix) {
            Arrays.fill(mix, 0.0f);
            Arrays.fill(bus, 0.0f);
            kickCount = 0;
            long blockEnd = position + blockSize;
            while (scheduling && nextLoopStart < blockEnd) {
                compileNextLoop();
            }
            updateStutter(blockEnd);
            long segmentStart = position;
            LiveHit hit;
            while ((hit = pollNext(blockEnd)) != null) {
                long frame = Math.max(segmentStart, hit.frame());
                renderVoices(voices, mix, bus, position, segmentStart, frame);
                segmentStart = frame;
                play(hit, frame);
            }
            renderVoices(voices, mix, bus, position, segmentStart, blockEnd);
            mixInBus(mix);
            position = blockEnd;
        }

        private void play(LiveHit hit, long frame) {
            int offset = (int) Math.max(0, frame - position);
            if (hit.kick() && (kickCount == 0 || kickOffsets[kickCount - 1] != offset)) {
                kickOffsets[kickCount++] = offset;
            }
            VoiceSlot slot = allocateVoice(voices, frame);
            if (hit.synth() != null) {
                slot.trigger(hit.synth().voice(hit.midiNote(), hit.heldFrames(), sampleRate), frame, hit.gain());
            } else {
                slot.trigger(hit.sample(), frame, hit.gain());
            }
        }

        /**
         * The next hit due before {@code blockEnd}, the song's or a repeat's, whichever comes first.
         * While a stutter holds, the song keeps going underneath — its hits are noted, so a new
         * slice can be taken from them — but past the end of the slice they are not played.
         */
        private LiveHit pollNext(long blockEnd) {
            while (true) {
                LiveHit song = hits.peekFirst();
                LiveHit repeat = repeats.peekFirst();
                boolean fromSong = song != null && (repeat == null || song.frame() <= repeat.frame());
                LiveHit next = fromSong ? song : repeat;
                if (next == null || next.frame() >= blockEnd) {
                    return null;
                }
                if (!fromSong) {
                    return repeats.pollFirst();
                }
                hits.pollFirst();
                recent.addLast(next);
                if (stutterBeats > 0 && next.frame() >= sliceStart + sliceFrames) {
                    continue;
                }
                return next;
            }
        }

        /**
         * Takes up a stutter asked for since the last block, or lets it go, and lines up the repeats
         * due in this block. The slice is the one of the grid this block starts in, so the repeat is
         * in time from its first pass; the rest of that slice plays as it happens, and only then does
         * it start over. Each repeat plays the slice's hits again — the drum samples, and fresh voices
         * for the synth's notes, which read the knobs as they are now.
         */
        private void updateStutter(long blockEnd) {
            double wanted = stutterRequested;
            if (wanted != stutterBeats) {
                repeats.clear();
                slice.clear();
                stutterBeats = 0;
                Position here = positionAt(position);
                if (wanted > 0 && here != null) {
                    double framesPerBeat = 60.0 * sampleRate / here.song().bpm();
                    sliceFrames = Math.max(1, Math.round(wanted * framesPerBeat));
                    long heard = Math.min(sliceFrames - 1, Math.round(here.beat() % wanted * framesPerBeat));
                    sliceStart = position - heard;
                    nextRepeat = sliceStart + sliceFrames;
                    stutterBeats = wanted;
                }
            }
            long keepFrom = position - (long) STUTTER_MEMORY_SECONDS * sampleRate;
            while (!recent.isEmpty() && recent.peekFirst().frame() < keepFrom) {
                recent.pollFirst();
            }
            if (stutterBeats == 0) {
                return;
            }
            long sliceEnd = sliceStart + sliceFrames;
            if (nextRepeat < blockEnd && nextRepeat == sliceEnd) {
                // the first repeat is due, so the whole slice is known: some of its hits already
                // played or passed over, the rest still waiting. Kept now, before they are forgotten.
                for (ArrayDeque<LiveHit> source : List.of(recent, hits)) {
                    for (LiveHit hit : source) {
                        if (hit.frame() >= sliceStart && hit.frame() < sliceEnd) {
                            slice.add(hit);
                        }
                    }
                }
            }
            while (nextRepeat < blockEnd) {
                for (LiveHit hit : slice) {
                    long into = hit.frame() - sliceStart;
                    repeats.addLast(hit.repeatedAt(nextRepeat + into, sliceFrames - into));
                }
                nextRepeat += sliceFrames;
            }
        }

        /**
         * The synth bus into the mix, through its effects when it has them. It runs on every block,
         * silence included, or a delay's repeats and a reverb's tail would stop with the last note.
         */
        private void mixInBus(float[] mix) {
            int nextKick = 0;
            for (int frame = 0; frame < blockSize; frame++) {
                float value = bus[frame * CHANNELS];
                if (effects == null) {
                    mix[frame * CHANNELS] += value;
                    mix[frame * CHANNELS + 1] += value;
                    continue;
                }
                if (nextKick < kickCount && kickOffsets[nextKick] == frame) {
                    effects.duck();
                    nextKick++;
                }
                effects.process(value, stereo);
                mix[frame * CHANNELS] += stereo[0];
                mix[frame * CHANNELS + 1] += stereo[1];
            }
        }

        Position positionAt(long frame) {
            Loops snapshot = loops;
            LoopMark mark = snapshot.current() != null && frame >= snapshot.current().startFrame()
                    ? snapshot.current()
                    : snapshot.previous();
            if (mark == null) {
                return null;
            }
            double beat = (frame - mark.startFrame()) * mark.song().bpm() / 60.0 / sampleRate;
            return new Position(mark.song(), Math.max(0.0, beat), mark.lengthBeats());
        }

        private void compileNextLoop() {
            Jam jam = jams.get();
            Song song = jam.song();
            Transport transport = new Transport(song.bpm(), sampleRate);
            double lengthBeats = PatternCompiler.totalBeats(song);
            long loopStart = nextLoopStart;
            for (Note note : PatternCompiler.compile(song)) {
                // a hit past the loop end would land after the next loop's first hits and break their order
                if (note.velocity() <= 0.0f || note.beat() >= lengthBeats) {
                    continue;
                }
                long frame = loopStart + transport.frameAtBeat(note.beat());
                if (externalMelody != null && note.voice() instanceof Voice.Pitch pitch) {
                    long offFrame = loopStart + transport.frameAtBeat(note.beat() + note.durationBeats());
                    // velocity 0 on a note-on would be read as a note-off by the receiving synth
                    int velocity = Math.max(1, Math.round(note.velocity() * 127.0f));
                    externalMelody.accept(new ExternalNote(frame, true, pitch.midiNote(), velocity));
                    externalMelody.accept(new ExternalNote(offFrame, false, pitch.midiNote(), 0));
                } else if (jam.synth() instanceof LivePitchSynth live && note.voice() instanceof Voice.Pitch pitch) {
                    if (effects == null) {
                        effects = live.effects(sampleRate);
                    }
                    // a voice of its own, reading the synth's parameters as it plays, so a knob
                    // moved now is heard in this note rather than in the loop after it
                    int heldFrames = (int) transport.frameAtBeat(note.durationBeats());
                    hits.addLast(LiveHit.note(frame, live, pitch.midiNote(), heldFrames, note.velocity()));
                } else {
                    hits.addLast(LiveHit.sample(frame, sampleFor(note, transport, jam.synth()), note.velocity(),
                            note.voice() == Drum.KICK));
                }
            }
            nextLoopStart = loopStart + Math.max(1L, transport.frameAtBeat(lengthBeats));
            loops = new Loops(loops.current(), new LoopMark(loopStart, song, lengthBeats));
        }

        private Sample sampleFor(Note note, Transport transport, PitchSynth synth) {
            return switch (note.voice()) {
                case Drum drum -> drumSample(drum, note, transport, shapeCache);
                case Voice.Pitch pitch -> {
                    int frameCount = (int) transport.frameAtBeat(note.durationBeats());
                    yield synthCache.computeIfAbsent(new LiveSynthKey(synth, pitch.midiNote(), frameCount),
                            key -> synth.render(key.midiNote(), key.frameCount(), sampleRate));
                }
            };
        }
    }

    /** A {@link LiveRenderer} playing to the default audio device until {@link #close()}. */
    public final class LiveSession implements AutoCloseable {
        private static final double MAX_TAIL_SECONDS = 12;

        private final LiveRenderer renderer;
        private final SourceDataLine line;
        private final NoteListener externalMelody;
        private final PriorityBlockingQueue<ExternalNote> pendingMelody = new PriorityBlockingQueue<>(
                64, Comparator.comparingLong(ExternalNote::frame).thenComparing(ExternalNote::on));
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread renderThread;
        private final Thread melodyThread;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean running = true;
        // the melody thread has a stop of its own: an external synth's notes end when the jam does,
        // even while our own tail is still ringing
        private volatile boolean sendingMelody = true;
        private volatile boolean releasing;
        private volatile double externalLatencyMillis;

        private LiveSession(Supplier<Jam> jams, NoteListener externalMelody)
                throws LineUnavailableException {
            this.externalMelody = externalMelody;
            this.renderer = new LiveRenderer(jams, externalMelody == null ? null : pendingMelody::add);
            AudioFormat format = new AudioFormat(sampleRate, 16, CHANNELS, true, false);
            this.line = AudioSystem.getSourceDataLine(format);
            // Twice the buffer play() uses: a loop that brings a new tempo renders its synth notes
            // on the audio thread, and the extra headroom keeps that from reaching the speakers.
            line.open(format, blockSize * CHANNELS * 2 * 8);
            line.start();
            this.renderThread = Thread.ofPlatform().name("live-render").daemon().start(this::render);
            this.melodyThread = externalMelody == null
                    ? null
                    : Thread.ofPlatform().name("live-melody").daemon().start(this::sendMelody);
        }

        public Position position() {
            return renderer.positionAt(line.getLongFramePosition());
        }

        /**
         * How long the external synth takes to sound a note it has received - its own audio buffer.
         * Melody notes are sent that much before their frame, so they are heard with the drums.
         */
        public void setExternalLatencyMillis(double millis) {
            externalLatencyMillis = millis;
        }

        /**
         * Holds a beat repeat over everything that plays — drums, synth and its effects — slicing
         * the grid into {@code beats}-long pieces (0.25 is a sixteenth); 0 lets go, and the jam
         * carries on from wherever it got to underneath.
         */
        public void stutter(double beats) {
            renderer.stutter(beats);
        }

        public Optional<Throwable> failure() {
            return Optional.ofNullable(failure.get());
        }

        /**
         * Stops the jam but keeps playing until what it left behind has died away: notes still in
         * their release, a delay's repeats, a reverb's tail. Returns at once — the tail rings on the
         * render thread, which closes the line itself when the sound falls silent (or after
         * {@link #MAX_TAIL_SECONDS}, so a delay set to repeat forever cannot hold the device).
         */
        public void release() {
            releasing = true;
            renderer.stopScheduling();
            // the external synth is not ours to let ring: its notes end now
            sendingMelody = false;
            join(melodyThread);
        }

        /** Stops everything now, tail and all. */
        @Override
        public void close() {
            running = false;
            sendingMelody = false;
            if (Thread.currentThread() != renderThread) {
                join(renderThread);
            }
            join(melodyThread);
            if (closed.compareAndSet(false, true)) {
                line.stop();
                line.flush();
                line.close();
            }
        }

        private void render() {
            float[] mix = new float[blockSize * CHANNELS];
            byte[] pcm = new byte[blockSize * CHANNELS * 2];
            long tailFrames = 0;
            try {
                while (running) {
                    renderer.renderNext(mix);
                    writeFully(line, pcm, encodePcm16(mix, blockSize, pcm));
                    if (!releasing) {
                        continue;
                    }
                    tailFrames += blockSize;
                    if (silent(mix) || tailFrames > MAX_TAIL_SECONDS * sampleRate) {
                        running = false;
                    }
                }
                if (releasing) {
                    line.drain();
                    close();
                }
            } catch (RuntimeException exception) {
                failure.compareAndSet(null, exception);
                running = false;
            }
        }

        /** Quiet enough that nobody would hear the rest of the tail (about -80 dB). */
        private boolean silent(float[] mix) {
            for (float value : mix) {
                if (Math.abs(value) > 1e-4f) {
                    return false;
                }
            }
            return true;
        }

        private void sendMelody() {
            Set<Integer> sounding = new HashSet<>();
            PlaybackClock clock = new PlaybackClock(line::getLongFramePosition, System::nanoTime, sampleRate);
            try {
                while (running && sendingMelody) {
                    ExternalNote next = pendingMelody.peek();
                    long leadFrames = Math.round(externalLatencyMillis * sampleRate / 1000.0);
                    if (next == null || next.frame() - leadFrames > clock.frameNow()) {
                        Thread.sleep(1);
                        continue;
                    }
                    ExternalNote note = pendingMelody.poll();
                    if (note.on()) {
                        externalMelody.noteOn(note.midiNote(), note.velocity());
                        sounding.add(note.midiNote());
                    } else {
                        externalMelody.noteOff(note.midiNote());
                        sounding.remove(note.midiNote());
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                failure.compareAndSet(null, exception);
                running = false;
            } finally {
                releaseAll(sounding);
            }
        }

        private void releaseAll(Set<Integer> sounding) {
            for (int pitch : sounding) {
                try {
                    externalMelody.noteOff(pitch);
                } catch (RuntimeException ignored) {
                    // Best effort: the device may already be gone.
                }
            }
        }

        private void join(Thread thread) {
            if (thread == null) {
                return;
            }
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record BarHit(double beat, Sample sample, float velocity) {
    }

    private record SynthKey(int midiNote, int frameCount) {
    }

    private record ShapeKey(Drum drum, Envelope envelope, int heldFrames) {
    }

    /** A drum's sample, shaped by its note's envelope when it has one, rendered once per shape. */
    private Sample drumSample(Drum drum, Note note, Transport transport, Map<ShapeKey, Sample> cache) {
        Envelope envelope = note.envelope();
        if (!envelope.shapes()) {
            return samples.sample(drum);
        }
        int heldFrames = envelope.releaseSeconds() > 0.0 ? (int) transport.frameAtBeat(note.durationBeats()) : -1;
        return cache.computeIfAbsent(new ShapeKey(drum, envelope, heldFrames),
                key -> samples.sample(drum).shaped(envelope, key.heldFrames(), sampleRate));
    }

    /** One sounding note: either a rendered {@link Sample} played back, or a live {@link VoiceSource}. */
    private static final class VoiceSlot {
        private Sample sample;
        private VoiceSource source;
        private long startFrame;
        private float gain;
        private boolean active;

        private void trigger(Sample sample, long startFrame, float gain) {
            this.sample = sample;
            this.source = null;
            this.startFrame = startFrame;
            this.gain = gain;
            this.active = true;
        }

        private boolean isLive() {
            return source != null;
        }

        private void trigger(VoiceSource source, long startFrame, float gain) {
            this.sample = null;
            this.source = source;
            this.startFrame = startFrame;
            this.gain = gain;
            this.active = true;
        }

        private long remainingFrames(long position) {
            if (source != null) {
                // a live voice has no length to compare, so it is the last thing a new note steals
                return Long.MAX_VALUE;
            }
            long played = Math.max(0L, position - startFrame);
            return sample.frameCount() - played;
        }

        private void render(float[] mix, long blockStart, long fromFrame, long toFrame) {
            long firstFrame = Math.max(fromFrame, startFrame);
            if (source != null) {
                renderSource(mix, blockStart, firstFrame, toFrame);
                return;
            }
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

        /** The frames come one at a time and in order, which is all {@link VoiceSource} promises. */
        private void renderSource(float[] mix, long blockStart, long firstFrame, long toFrame) {
            for (long frame = firstFrame; frame < toFrame; frame++) {
                if (source.finished()) {
                    active = false;
                    source = null;
                    return;
                }
                float value = source.next() * gain;
                int outputFrame = (int) (frame - blockStart);
                mix[outputFrame * CHANNELS] += value;
                mix[outputFrame * CHANNELS + 1] += value;
            }
        }
    }
}
