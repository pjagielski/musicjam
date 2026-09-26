package pl.livecoding.musicjam.audio;

import pl.livecoding.musicjam.model.Drum;
import pl.livecoding.musicjam.model.Envelope;
import pl.livecoding.musicjam.model.LoopTrack;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
     * boundary, with the same sample accuracy as {@link #play}. The song's tempo and loop length are
     * read every block and taken up at once, in the loop that is playing. When {@code externalMelody} is not
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
                renderVoices(voices, mix, null, position, segmentStart, eventFrame);
                do {
                    BarHit hit = barHits[nextHit];
                    allocateVoice(voices, eventFrame).trigger(hit.sample, eventFrame, hit.velocity);
                    advanceEvent();
                } while (hasNextEvent() && nextEventFrame() == eventFrame);
                segmentStart = eventFrame;
            }
            renderVoices(voices, mix, null, position, segmentStart, blockEnd);
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

    /**
     * Each voice into the mix, or into its synth's own bus when it has one, so that synth's effects
     * can be put across its voices alone. With {@code gains}, each voice is played at its track's
     * gain as it stands in this block.
     */
    private static void renderVoices(VoiceSlot[] voices, float[] mix, TrackGains gains,
                                     long blockStart, long fromFrame, long toFrame) {
        for (VoiceSlot voice : voices) {
            if (voice.active) {
                voice.render(voice.out != null ? voice.out : mix, gains, blockStart, fromFrame, toFrame);
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

    LiveRenderer liveRenderer(Supplier<Jam> jams, boolean externalMelody) {
        return new LiveRenderer(jams, externalMelody, true);
    }

    LiveRenderer idleRenderer(Supplier<Jam> jams) {
        return new LiveRenderer(jams, false, false);
    }

    /**
     * A session that plays no loop: it renders silence, and sounds only what is
     * {@link LiveSession#audition auditioned} through it. It is what a key pressed on the piano
     * roll plays through while the jam is stopped, so a note can be heard before it is written.
     */
    public LiveSession openIdle(Supplier<Jam> jam) throws LineUnavailableException {
        return new LiveSession(jam, null, false);
    }

    /**
     * What is audible right now: the song of the loop being heard, how far into that loop, and the
     * tempo it is playing at — which can be newer than the song's own, as a tempo is taken up at once.
     */
    public record Position(Song song, double beat, double lengthBeats, double bpm) {
    }

    /**
     * One loop's worth of what to play live: the song, the synth each of its tracks is played by,
     * and the MIDI channel each is sent to instead, in the same order. A drum track's synth plays
     * nothing, as its notes are samples. A live synth is known by its identity: the same one from
     * jam to jam keeps its channel and its effects' tails, a new one gets a channel of its own.
     *
     * <p>A track's melody goes to an external synth when it has a channel, counting from 0 as MIDI
     * messages do, and the session has somewhere to send it; {@link #HERE} plays it here. Its
     * notes are routed as a loop is queued, so a track sent out or brought back is heard so from
     * the next loop on.
     */
    public record Jam(Song song, List<PitchSynth> synths, List<Integer> channels) {

        /** The channel of a track played here rather than sent out. */
        public static final int HERE = -1;

        public Jam {
            Objects.requireNonNull(song, "song");
            synths = List.copyOf(synths);
            channels = List.copyOf(channels);
            if (synths.isEmpty() || channels.isEmpty()) {
                throw new IllegalArgumentException("A jam needs a synth and a channel");
            }
            for (int channel : channels) {
                if (channel < HERE || channel > 15) {
                    throw new IllegalArgumentException("A MIDI channel counts from 0 to 15, not " + channel);
                }
            }
        }

        /** Every track played here, each by its own synth. */
        public Jam(Song song, List<PitchSynth> synths) {
            this(song, synths, List.of(HERE));
        }

        /** One synth for every track, played here. */
        public Jam(Song song, PitchSynth synth) {
            this(song, Collections.nCopies(Math.max(1, song.tracks().size()), synth));
        }

        /** The synth that plays {@code track}'s notes; the last one for a track the list is short of. */
        public PitchSynth synthFor(int track) {
            return synths.get(Math.min(track, synths.size() - 1));
        }

        /** The channel {@code track}'s notes go out on, or {@link #HERE}; the last one for a track the list is short of. */
        public int channelFor(int track) {
            return channels.get(Math.min(track, channels.size() - 1));
        }
    }

    /**
     * A live synth's own channel: its voices are summed here and go through its own effects on the
     * way to the mix, so two tracks' delays and reverbs are two, each with its own tail.
     */
    private final class SynthBus {
        final float[] frames = new float[blockSize * CHANNELS];
        final AudioEffect effects;
        // how many blocks in a row it has put out nothing: a bus the jam has let go of goes once
        // its tail has died away
        int quietBlocks;

        SynthBus(LivePitchSynth synth) {
            this.effects = synth.effects(sampleRate);
        }
    }

    private record LiveSynthKey(PitchSynth synth, int midiNote, int frameCount) {
    }

    /**
     * A melody note for an external synth, placed in beats from the start of the jam, so it moves
     * with the tempo until it is sent. {@code startBeat} is the beat of the note it belongs to: its
     * own for a note-on, the note-on's for a note-off. {@code velocity} is the note's own, before
     * its track's gain, which is applied as it is sent: a note of a muted track is not sent at all.
     */
    record ExternalNote(double beat, boolean on, int midiNote, int velocity, double startBeat, int track,
                        int channel) {
    }

    /**
     * The velocity a note-on goes out with at its track's {@code gain} as it is when sent, or 0
     * when it is not to be sent at all: its track is muted. Never 0 otherwise, which a synth would
     * read as a note-off.
     */
    static int sentVelocity(ExternalNote note, float gain) {
        return gain <= 0.0f ? 0 : Math.max(1, Math.round(note.velocity() * gain));
    }

    /**
     * A hit waiting for its beat: a rendered sample, or a note for a live synth, whose voice is made
     * when the hit is played. Keeping the recipe rather than the voice is what lets a stutter play
     * the same note again, through the synth as its knobs are now; keeping beats rather than frames
     * is what lets a new tempo move every hit not yet played.
     */
    private record LiveHit(double beat, Sample sample, LivePitchSynth synth, int midiNote, double heldBeats,
                           float gain, boolean kick, int track, Supplier<LoopVoice> loop) {

        static LiveHit sample(double beat, Sample sample, float gain, boolean kick, int track) {
            return new LiveHit(beat, sample, null, 0, 0, gain, kick, track, null);
        }

        static LiveHit note(double beat, LivePitchSynth synth, int midiNote, double heldBeats, float gain, int track) {
            return new LiveHit(beat, null, synth, midiNote, heldBeats, gain, false, track, null);
        }

        /** A loop starting its pass: the voice is made when it is due, so it reads the tempo as it then is. */
        static LiveHit loop(double beat, Supplier<LoopVoice> loop, int track) {
            return new LiveHit(beat, null, null, 0, 0, 1.0f, false, track, loop);
        }

        /** The same hit at {@code at}, held no longer than {@code room}: a stutter chops, it does not smear. */
        LiveHit repeatedAt(double at, double room) {
            return new LiveHit(at, sample, synth, midiNote, Math.min(heldBeats, room), gain, kick, track, loop);
        }
    }

    /** A note of one loop and the track it comes from. */
    private record TrackNote(Note note, int track) {
    }

    /** A note asked for by hand, to be sounded at the next block rather than at a beat. */
    private record Audition(PitchSynth synth, int midiNote, int frames, float velocity) {
    }

    /** A loop, from the beat of the jam it starts on; {@code lengthBeats} is how long it will actually run. */
    private record LoopMark(double startBeat, Song song, double lengthBeats) {
    }

    private record Loops(LoopMark previous, LoopMark current) {
    }

    /**
     * Renders an endless song block by block. A loop is compiled from whatever the supplier returns
     * when the block about to be rendered reaches that loop's first beat; within the loop every hit
     * is placed at its exact frame, as in {@link RenderSession}. Two things are taken up sooner, at
     * the next block: the tempo, which re-times every hit not yet played, and the loop's length,
     * which cuts the loop short or runs it on (see {@link #followLoopLength}). The render position
     * never resets.
     */
    final class LiveRenderer {
        // how far back a stutter can reach for its slice: more than the longest slice
        private static final double STUTTER_MEMORY_BEATS = 16;

        private final Supplier<Jam> jams;
        private final PriorityBlockingQueue<ExternalNote> externalMelody;
        private final Map<LiveSynthKey, Sample> synthCache = new HashMap<>();
        private final Map<ShapeKey, Sample> shapeCache = new HashMap<>();
        private final VoiceSlot[] voices = new VoiceSlot[maxVoices];
        private final ArrayDeque<LiveHit> hits = new ArrayDeque<>();
        // a channel for each live synth, kept apart from the drums and from each other so each
        // synth's effects colour only what it plays; in the order the synths first played
        private final Map<LivePitchSynth, SynthBus> buses = new LinkedHashMap<>();
        // the synths of the jam as it was last read, whose buses stay however quiet they are
        private List<PitchSynth> playing = List.of();
        private final float[] stereo = new float[CHANNELS];
        // where in this block a kick landed: the sidechain's key, played to the effects in time
        private final int[] kickOffsets = new int[blockSize];
        private int kickCount;
        // the song's hits of the last few bars, played or not: where a stutter's slice comes from
        private final ArrayDeque<LiveHit> recent = new ArrayDeque<>();
        // a stutter's repeats of its slice, due at their beats alongside the song's own hits
        private final ArrayDeque<LiveHit> repeats = new ArrayDeque<>();
        // the slice's hits, kept from its first repeat on: the song's memory moves on, the slice must not
        private final List<LiveHit> slice = new ArrayList<>();
        // the effects played over the finished mix, drums and synth together
        private final PerformanceFx performance = new PerformanceFx(sampleRate);
        // each track's gain, read from the jam every block and applied to its voices as they play
        private final TrackGains gains = new TrackGains(blockSize);
        // the pass each loop track is playing, so starting it again can end the one before it
        private final Map<Integer, LoopVoice> loopVoices = new HashMap<>();
        private volatile double stutterRequested;
        private double stutterBeats;
        private double sliceStart;
        private double nextRepeat;
        private volatile Loops loops = new Loops(null, null);
        private volatile TempoMap tempo = TempoMap.starting(120, sampleRate);
        private volatile boolean scheduling;
        // notes to sound at the next block, from a key pressed on the roll rather than from a loop
        private final Queue<Audition> auditions = new ConcurrentLinkedQueue<>();
        private long position;
        private double nextLoopBeat;

        /**
         * With {@code externalMelody}, the melody is queued for an external synth rather than
         * played. Without {@code scheduling} no loop is ever compiled: the renderer runs, so
         * voices and effects do, but the jam itself stays silent.
         */
        private LiveRenderer(Supplier<Jam> jams, boolean externalMelody, boolean scheduling) {
            this.scheduling = scheduling;
            this.jams = jams;
            this.externalMelody = externalMelody
                    ? new PriorityBlockingQueue<>(64, Comparator.comparingDouble(ExternalNote::beat)
                            .thenComparing(ExternalNote::on))
                    : null;
            for (int i = 0; i < voices.length; i++) {
                voices[i] = new VoiceSlot();
            }
        }

        /** The melody's notes for an external synth, earliest first; null when the melody plays here. */
        PriorityBlockingQueue<ExternalNote> externalMelody() {
            return externalMelody;
        }

        PerformanceFx performance() {
            return performance;
        }

        /** A track's gain as the jam has it now. Safe from any thread. */
        float trackGain(int track) {
            return gains.now(track);
        }

        /** The frame {@code beat} of the jam falls on, at the tempo as it stands. Safe from any thread. */
        long frameAt(double beat) {
            return tempo.frameAt(beat);
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
         * stops with it, or its repeats would never let the tail end, and so do the other
         * performance effects.
         */
        void stopScheduling() {
            scheduling = false;
            stutterRequested = 0;
            performance.releaseAll();
            hits.clear();
        }

        void renderNext(float[] mix) {
            Arrays.fill(mix, 0.0f);
            for (SynthBus bus : buses.values()) {
                Arrays.fill(bus.frames, 0.0f);
            }
            kickCount = 0;
            long blockEnd = position + blockSize;
            for (Audition asked = auditions.poll(); asked != null; asked = auditions.poll()) {
                sound(asked);
            }
            if (scheduling) {
                Jam jam = jams.get();
                playing = jam.synths();
                gains.next(jam.song());
                tempo = tempo.at(position, jam.song().bpm());
                followLoopLength(jam);
                while (tempo.frameAt(nextLoopBeat) < blockEnd) {
                    compileNextLoop(jam);
                }
            } else {
                gains.hold();
            }
            updateStutter(blockEnd);
            long segmentStart = position;
            LiveHit hit;
            while ((hit = pollNext(blockEnd)) != null) {
                long frame = Math.max(segmentStart, tempo.frameAt(hit.beat()));
                renderVoices(voices, mix, gains, position, segmentStart, frame);
                segmentStart = frame;
                play(hit, frame);
            }
            renderVoices(voices, mix, gains, position, segmentStart, blockEnd);
            mixInBuses(mix);
            performance.process(mix, blockSize, tempo.bpm());
            position = blockEnd;
        }

        /**
         * Sounds a note now, outside the loop: through its synth's own channel, so it is heard
         * with that track's effects, and at full gain whatever the track's fader says — it is
         * being listened to, not played.
         */
        private void sound(Audition asked) {
            VoiceSlot slot = allocateVoice(voices, position);
            if (asked.synth() instanceof LivePitchSynth live) {
                slot.trigger(live.voice(asked.midiNote(), asked.frames(), sampleRate), position, asked.velocity());
                slot.out = busFor(live).frames;
            } else {
                slot.trigger(asked.synth().render(asked.midiNote(), asked.frames(), sampleRate), position,
                        asked.velocity());
            }
        }

        /** Sounds {@code midiNote} through {@code synth} at the next block. Safe from any thread. */
        void audition(PitchSynth synth, int midiNote, double seconds, float velocity) {
            auditions.add(new Audition(synth, midiNote, (int) Math.round(seconds * sampleRate), velocity));
        }

        private void play(LiveHit hit, long frame) {
            int offset = (int) Math.max(0, frame - position);
            if (hit.kick() && (kickCount == 0 || kickOffsets[kickCount - 1] != offset)) {
                kickOffsets[kickCount++] = offset;
            }
            VoiceSlot slot = allocateVoice(voices, frame);
            if (hit.loop() != null) {
                // a loop starting again ends the pass still playing, rather than doubling with it
                LoopVoice playing = loopVoices.get(hit.track());
                if (playing != null) {
                    playing.stop();
                }
                LoopVoice voice = hit.loop().get();
                loopVoices.put(hit.track(), voice);
                slot.trigger(voice, frame, hit.gain());
                slot.track = hit.track();
                return;
            }
            if (hit.synth() != null) {
                // the note's length at the tempo it starts in
                int heldFrames = (int) tempo.frames(hit.heldBeats());
                slot.trigger(hit.synth().voice(hit.midiNote(), heldFrames, sampleRate), frame, hit.gain());
                slot.out = busFor(hit.synth()).frames;
            } else {
                slot.trigger(hit.sample(), frame, hit.gain());
            }
            slot.track = hit.track();
        }

        private SynthBus busFor(LivePitchSynth synth) {
            return buses.computeIfAbsent(synth, SynthBus::new);
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
                boolean fromSong = song != null && (repeat == null || song.beat() <= repeat.beat());
                LiveHit next = fromSong ? song : repeat;
                if (next == null || tempo.frameAt(next.beat()) >= blockEnd) {
                    return null;
                }
                if (!fromSong) {
                    return repeats.pollFirst();
                }
                hits.pollFirst();
                recent.addLast(next);
                if (stutterBeats > 0 && next.beat() >= sliceStart + stutterBeats) {
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
            double now = tempo.beatAt(position);
            double wanted = stutterRequested;
            if (wanted != stutterBeats) {
                repeats.clear();
                slice.clear();
                stutterBeats = 0;
                Loops snapshot = loops;
                // the loop this block starts in, which is not the one compiled for later in the block
                LoopMark loop = snapshot.current() != null && snapshot.current().startBeat() > now
                        ? snapshot.previous()
                        : snapshot.current();
                if (wanted > 0 && loop != null) {
                    sliceStart = now - Math.max(0.0, now - loop.startBeat()) % wanted;
                    nextRepeat = sliceStart + wanted;
                    stutterBeats = wanted;
                }
            }
            while (!recent.isEmpty() && recent.peekFirst().beat() < now - STUTTER_MEMORY_BEATS) {
                recent.pollFirst();
            }
            if (stutterBeats == 0) {
                return;
            }
            double sliceEnd = sliceStart + stutterBeats;
            if (tempo.frameAt(nextRepeat) < blockEnd && nextRepeat == sliceEnd) {
                // the first repeat is due, so the whole slice is known: some of its hits already
                // played or passed over, the rest still waiting. Kept now, before they are forgotten.
                for (ArrayDeque<LiveHit> source : List.of(recent, hits)) {
                    for (LiveHit hit : source) {
                        if (hit.beat() >= sliceStart && hit.beat() < sliceEnd) {
                            slice.add(hit);
                        }
                    }
                }
            }
            while (tempo.frameAt(nextRepeat) < blockEnd) {
                for (LiveHit hit : slice) {
                    double into = hit.beat() - sliceStart;
                    repeats.addLast(hit.repeatedAt(nextRepeat + into, stutterBeats - into));
                }
                nextRepeat += stutterBeats;
            }
        }

        /**
         * Every synth's bus into the mix, each through its own effects when it has them, each ducking
         * under the kick. They run on every block, silence included, or a delay's repeats and a
         * reverb's tail would stop with the last note.
         */
        private void mixInBuses(float[] mix) {
            // a second of silence, time enough for a tail to be over
            int longQuiet = Math.max(1, sampleRate / blockSize);
            var each = buses.entrySet().iterator();
            while (each.hasNext()) {
                var entry = each.next();
                SynthBus bus = entry.getValue();
                float loudest = 0.0f;
                int nextKick = 0;
                for (int frame = 0; frame < blockSize; frame++) {
                    float left = bus.frames[frame * CHANNELS];
                    float right = left;
                    if (bus.effects != null) {
                        if (nextKick < kickCount && kickOffsets[nextKick] == frame) {
                            bus.effects.duck();
                            nextKick++;
                        }
                        bus.effects.process(left, stereo);
                        left = stereo[0];
                        right = stereo[1];
                    }
                    mix[frame * CHANNELS] += left;
                    mix[frame * CHANNELS + 1] += right;
                    loudest = Math.max(loudest, Math.max(Math.abs(left), Math.abs(right)));
                }
                bus.quietBlocks = loudest < 1e-5f ? bus.quietBlocks + 1 : 0;
                if (bus.quietBlocks > longQuiet && !playing.contains(entry.getKey()) && !anyVoiceInto(bus)) {
                    each.remove();
                }
            }
        }

        private boolean anyVoiceInto(SynthBus bus) {
            for (VoiceSlot voice : voices) {
                if (voice.active && voice.out == bus.frames) {
                    return true;
                }
            }
            return false;
        }

        /** How many synth channels are open: one per live synth the jam has, and any still dying away. */
        int busCount() {
            return buses.size();
        }

        Position positionAt(long frame) {
            Loops snapshot = loops;
            TempoMap map = tempo;
            double beat = map.beatAt(frame);
            LoopMark mark = snapshot.current() != null && beat >= snapshot.current().startBeat()
                    ? snapshot.current()
                    : snapshot.previous();
            if (mark == null) {
                return null;
            }
            return new Position(mark.song(), Math.max(0.0, beat - mark.startBeat()), mark.lengthBeats(),
                    map.bpmAt(frame));
        }

        /**
         * Takes up a new loop length in the loop that is playing, rather than waiting for its end. A
         * shorter loop wraps at the first multiple of its length still ahead — set to 4 bars in bar 3
         * of 8, it wraps after bar 4; in bar 5, after bar 8 — and the hits queued past that point are
         * dropped. A longer one runs on: the longer song's hits past the old end are queued, and the
         * loop ends where the longer song does.
         */
        private void followLoopLength(Jam jam) {
            LoopMark loop = loops.current();
            if (loop == null) {
                return;
            }
            double wanted = lengthOf(jam.song());
            double length = loop.lengthBeats();
            if (wanted == length) {
                return;
            }
            double into = tempo.beatAt(position) - loop.startBeat();
            if (wanted > length) {
                queue(jam, loop.startBeat(), length, wanted);
                setLength(loop, jam.song(), wanted);
                return;
            }
            double end = Math.max(1.0, Math.ceil(into / wanted)) * wanted;
            if (end >= length) {
                return;
            }
            double cut = loop.startBeat() + end;
            hits.removeIf(hit -> hit.beat() >= cut);
            if (externalMelody != null) {
                externalMelody.removeIf(note -> note.startBeat() >= cut);
            }
            setLength(loop, jam.song(), end);
        }

        private void setLength(LoopMark loop, Song song, double lengthBeats) {
            nextLoopBeat = loop.startBeat() + lengthBeats;
            loops = new Loops(loops.previous(), new LoopMark(loop.startBeat(), song, lengthBeats));
        }

        private void compileNextLoop(Jam jam) {
            double loopStart = nextLoopBeat;
            double lengthBeats = lengthOf(jam.song());
            queue(jam, loopStart, 0.0, lengthBeats);
            nextLoopBeat = loopStart + lengthBeats;
            loops = new Loops(loops.current(), new LoopMark(loopStart, jam.song(), lengthBeats));
        }

        /** A loop's length in beats, never less than a frame at the tempo now, so a loop always moves on. */
        private double lengthOf(Song song) {
            return Math.max(PatternCompiler.totalBeats(song), 1.0 / tempo.frames(1.0));
        }

        /** Queues the song's hits from {@code fromBeat} up to {@code toBeat} of a loop starting at {@code loopStart}. */
        private void queue(Jam jam, double loopStart, double fromBeat, double toBeat) {
            Song song = jam.song();
            Transport transport = new Transport(song.bpm(), sampleRate);
            List<List<Note>> byTrack = PatternCompiler.compileByTrack(song);
            List<TrackNote> loop = new ArrayList<>();
            for (int track = 0; track < byTrack.size(); track++) {
                for (Note note : byTrack.get(track)) {
                    loop.add(new TrackNote(note, track));
                }
            }
            // in order of their beats across the tracks, which is the order the hits are played in
            loop.sort(Comparator.comparingDouble(trackNote -> trackNote.note().beat()));
            for (int track = 0; track < song.tracks().size(); track++) {
                if (song.tracks().get(track) instanceof LoopTrack audio) {
                    queueLoop(audio, track, song, loopStart, fromBeat, toBeat);
                }
            }
            for (TrackNote trackNote : loop) {
                Note note = trackNote.note();
                int track = trackNote.track();
                // a hit past the loop end would land after the next loop's first hits and break their order
                if (note.velocity() <= 0.0f || note.beat() < fromBeat || note.beat() >= toBeat) {
                    continue;
                }
                double beat = loopStart + note.beat();
                int channel = jam.channelFor(track);
                if (externalMelody != null && channel != Jam.HERE && note.voice() instanceof Voice.Pitch pitch) {
                    // velocity 0 on a note-on would be read as a note-off by the receiving synth
                    int velocity = Math.max(1, Math.round(note.velocity() * 127.0f));
                    externalMelody.add(new ExternalNote(beat, true, pitch.midiNote(), velocity, beat, track, channel));
                    externalMelody.add(new ExternalNote(beat + note.durationBeats(), false, pitch.midiNote(), 0, beat,
                            track, channel));
                } else if (jam.synthFor(track) instanceof LivePitchSynth live
                        && note.voice() instanceof Voice.Pitch pitch) {
                    // its bus from the first note on, so its effects are there before the note sounds
                    busFor(live);
                    // a voice of its own, reading the synth's parameters as it plays, so a knob
                    // moved now is heard in this note rather than in the loop after it
                    hits.addLast(LiveHit.note(beat, live, pitch.midiNote(), note.durationBeats(), note.velocity(),
                            track));
                } else {
                    hits.addLast(LiveHit.sample(beat, sampleFor(note, transport, jam.synthFor(track)), note.velocity(),
                            note.voice() == Drum.KICK, track));
                }
            }
        }

        /**
         * A loop starts at the top of the jam's loop and every {@code bars} bars after it, for as
         * long as the loop runs. The rate is read as it plays rather than worked out here, so a
         * tempo change re-times the pass under way.
         */
        private void queueLoop(LoopTrack audio, int track, Song song, double loopStart, double fromBeat,
                               double toBeat) {
            double pass = audio.lengthBeats(song.beatsPerBar());
            for (double at = 0; at < toBeat; at += pass) {
                if (at < fromBeat) {
                    continue;
                }
                hits.addLast(LiveHit.loop(loopStart + at,
                        () -> new LoopVoice(audio.audio(), () -> audio.audio().frameCount() / tempo.frames(pass)),
                        track));
            }
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
            this(jams, externalMelody, true);
        }

        private LiveSession(Supplier<Jam> jams, NoteListener externalMelody, boolean scheduling)
                throws LineUnavailableException {
            this.externalMelody = externalMelody;
            this.renderer = new LiveRenderer(jams, externalMelody != null, scheduling);
            AudioFormat format = new AudioFormat(sampleRate, 16, CHANNELS, true, false);
            this.line = AudioSystem.getSourceDataLine(format);
            // Twice the buffer play() uses: a loop that brings a new synth renders its notes on the
            // audio thread, and the extra headroom keeps that from reaching the speakers.
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
        /**
         * Sounds one note now, through {@code synth}'s own channel: a key pressed on the roll,
         * heard whether the jam is playing or the session is an idle one. It lasts
         * {@code seconds}, since a voice is made with its length rather than let go of by hand.
         */
        public void audition(PitchSynth synth, int midiNote, double seconds, float velocity) {
            renderer.audition(synth, midiNote, seconds, velocity);
        }

        public void stutter(double beats) {
            renderer.stutter(beats);
        }

        /** The effects held over everything that plays: Crush, Filter and the rest of the Perform screen. */
        public PerformanceFx performance() {
            return renderer.performance();
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
            // each key a channel and a pitch, as the note-ons sent make them
            Set<Integer> sounding = new HashSet<>();
            PlaybackClock clock = new PlaybackClock(line::getLongFramePosition, System::nanoTime, sampleRate);
            PriorityBlockingQueue<ExternalNote> pendingMelody = renderer.externalMelody();
            try {
                while (running && sendingMelody) {
                    ExternalNote next = pendingMelody.peek();
                    long leadFrames = Math.round(externalLatencyMillis * sampleRate / 1000.0);
                    // placed in beats until now, so a note waiting here moves with a new tempo
                    if (next == null || renderer.frameAt(next.beat()) - leadFrames > clock.frameNow()) {
                        Thread.sleep(1);
                        continue;
                    }
                    ExternalNote note = pendingMelody.poll();
                    // a pitch on a channel: two tracks on two channels can hold the same note
                    int key = note.channel() * 128 + note.midiNote();
                    if (note.on()) {
                        int velocity = sentVelocity(note, renderer.trackGain(note.track()));
                        if (velocity > 0) {
                            externalMelody.noteOn(note.channel(), note.midiNote(), velocity);
                            sounding.add(key);
                        }
                    } else if (sounding.contains(key)) {
                        externalMelody.noteOff(note.channel(), note.midiNote());
                        sounding.remove(key);
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
            for (int key : sounding) {
                try {
                    externalMelody.noteOff(key / 128, key % 128);
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
        // what a voice writes into, one frame at a time: a stereo sample keeps its two channels
        private final float[] stereo = new float[CHANNELS];
        // the track the voice plays for, whose gain it follows; -1 for none
        private int track = -1;
        // the synth bus it plays into, or null for the mix itself
        private float[] out;
        private boolean active;

        private void trigger(Sample sample, long startFrame, float gain) {
            this.track = -1;
            this.out = null;
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
            this.track = -1;
            this.out = null;
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

        private void render(float[] mix, TrackGains gains, long blockStart, long fromFrame, long toFrame) {
            long firstFrame = Math.max(fromFrame, startFrame);
            if (source != null) {
                renderSource(mix, gains, blockStart, firstFrame, toFrame);
                return;
            }
            long sampleFrame = firstFrame - startFrame;
            for (long frame = firstFrame; frame < toFrame && sampleFrame < sample.frameCount();
                 frame++, sampleFrame++) {
                int outputFrame = (int) (frame - blockStart);
                float value = sample.valueAt((int) sampleFrame) * gain * trackGain(gains, outputFrame);
                mix[outputFrame * CHANNELS] += value;
                mix[outputFrame * CHANNELS + 1] += value;
            }
            if (sampleFrame >= sample.frameCount()) {
                active = false;
            }
        }

        /** The frames come one at a time and in order, which is all {@link VoiceSource} promises. */
        private void renderSource(float[] mix, TrackGains gains, long blockStart, long firstFrame, long toFrame) {
            for (long frame = firstFrame; frame < toFrame; frame++) {
                if (source.finished()) {
                    active = false;
                    source = null;
                    return;
                }
                int outputFrame = (int) (frame - blockStart);
                float level = gain * trackGain(gains, outputFrame);
                source.next(stereo);
                mix[outputFrame * CHANNELS] += stereo[0] * level;
                mix[outputFrame * CHANNELS + 1] += stereo[1] * level;
            }
        }

        private float trackGain(TrackGains gains, int offset) {
            return gains == null ? 1.0f : gains.at(track, offset);
        }
    }
}
