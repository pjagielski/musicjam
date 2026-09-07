package pl.livecoding.musicjam;

import pl.livecoding.musicjam.audio.WavSampleLoader;
import pl.livecoding.musicjam.synth.AnthemLeadSynth;
import pl.livecoding.musicjam.synth.PitchSynth;
import pl.livecoding.musicjam.synth.TrancePluckSynth;
import pl.livecoding.musicjam.synth.WidePadSynth;

import javax.sound.midi.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Beat.java - maszyna perkusyjna oparta o render blokowy, plus melodia z pliku MIDI.
 *
 *   .\gradlew.bat beat                        -> gra na zywo (sterowanie z stdin)
 *   .\gradlew.bat beat --args="--wav out.wav 8" -> renderuje 8 taktow do pliku
 *   -Dsynth=pluck|anthem|pad -Dmid.track=1 -Dmid.fromBar=0 -Dmid.bars=4 -Dmel.hold=0
 *
 * Zegarem jest line.write() - blokuje, gdy bufor karty jest pelny.
 * Zdarzenia startuja z offsetem WEWNATRZ bloku, nie na jego granicy.
 *
 * Patch melodii pochodzi z pakietu synth, wiec to juz nie jest jeden plik:
 * source-launch dziala dopiero na JDK 22+, ktory kompiluje tez klasy obok.
 */
public class Beat {

    static final int SR = 44100;      // sample rate
    static final int BLOCK = 512;     // ramek na blok (~11.6 ms)
    static final int MAX_VOICES = 32;
    static final double BAR_BEATS = 4.0;

    // ---------------------------------------------------------------- model

    /** Sampel: mono, float w zakresie [-1, 1]. */
    record Sample(String name, float[] data) {}

    /** Sciezka: sampel + wzorzec krokow. Dlugosc stringa = liczba krokow na takt. */
    static final class Track {
        final Sample sample;
        volatile String steps;
        volatile float gain;
        long nextStep = 0;            // absolutny indeks kroku (nigdy nie resetowany)

        Track(Sample s, String steps, float gain) {
            this.sample = s; this.steps = steps; this.gain = gain;
        }
    }

    /** Glos: jedno odtwarzanie sampla zaczynajace sie w konkretnej ramce. */
    static final class Voice {
        Sample sample;
        long startFrame;
        float gain;
        boolean active;

        void trigger(Sample s, long startFrame, float gain) {
            this.sample = s; this.startFrame = startFrame; this.gain = gain; this.active = true;
        }

        /** Miksuje sie do bufora. blockStart = absolutna ramka poczatku bloku. */
        void render(float[] mix, long blockStart) {
            float[] d = sample.data();
            // offset wewnatrz bloku - TO jest cala precyzja
            int from = (int) Math.max(0, startFrame - blockStart);
            long idx = blockStart + from - startFrame;
            for (int i = from; i < BLOCK; i++, idx++) {
                if (idx >= d.length) { active = false; return; }
                float v = d[(int) idx] * gain;
                mix[i * 2]     += v;
                mix[i * 2 + 1] += v;
            }
        }
    }

    // ------------------------------------------------------------ scheduler

    static volatile double bpm = 96;
    static final AtomicReference<List<Track>> tracks = new AtomicReference<>(List.of());

    /** Absolutna ramka kroku - liczona zawsze od zera, nigdy przyrostowo. */
    static long frameOf(long step, int stepsPerBar) {
        double beat = step * BAR_BEATS / stepsPerBar;
        return Math.round(beat * 60.0 / bpm * SR);
    }

    /** Odpala wszystkie kroki wpadajace w [pos, pos+BLOCK). */
    static void schedule(long pos, Voice[] pool) {
        for (Track t : tracks.get()) {
            String pat = t.steps;
            int n = pat.length();
            if (n == 0) continue;
            while (frameOf(t.nextStep, n) < pos + BLOCK) {
                long f = frameOf(t.nextStep, n);
                if (f >= pos) {
                    char c = pat.charAt((int) (t.nextStep % n));
                    if (c != '.' && c != '-' && c != ' ') {
                        float accent = (c == 'X') ? 1.0f : (c == 'o') ? 0.5f : 0.8f;
                        alloc(pool).trigger(t.sample, f, t.gain * accent);
                    }
                }
                t.nextStep++;
            }
        }
    }

    static Voice alloc(Voice[] pool) {
        for (Voice v : pool) if (!v.active) return v;
        return pool[0]; // voice stealing
    }

    // ------------------------------------------------------------- melodia

    /** Best liczony wzgledem poczatku petli. */
    record MNote(double beat, Sample sample, float gain) {}

    static List<MNote> melody = List.of();
    static double melodyBeats = 0;
    static volatile float melodyGain = 0.45f;

    static int melNext = 0;
    static long melLoop = 0;

    /**
     * Ile nuty trzymamy, niezaleznie od jej dlugosci w pliku; 0 = tyle, ile w pliku.
     * Ogon dokleja release patcha (pluck 0.07 s, anthem 0.11 s, pad 1.5 s).
     * Jedna wartosc na sciezke.
     */
    static double[] holds(String spec) {
        return Arrays.stream(spec.split(",")).map(String::trim).mapToDouble(Double::parseDouble).toArray();
    }

    static long melodyFrameOf(int i) {
        double beat = melLoop * melodyBeats + melody.get(i).beat();
        return Math.round(beat * 60.0 / bpm * SR);
    }

    static void scheduleMelody(long pos, Voice[] pool) {
        if (melody.isEmpty()) return;
        while (melodyFrameOf(melNext) < pos + BLOCK) {
            long f = melodyFrameOf(melNext);
            MNote n = melody.get(melNext);
            if (f >= pos) alloc(pool).trigger(n.sample(), f, n.gain() * melodyGain);
            if (++melNext == melody.size()) { melNext = 0; melLoop++; }
        }
    }

    /**
     * Tempo bierzemy z pliku - melodia i perkusja maja isc w jednym tempie.
     * Kazda sciezka dostaje wlasny czas trzymania nuty, wiec akordy moga wybrzmiewac
     * dluzej niz arpeggio grane po wierzchu.
     */
    static void loadMelody(String path, int[] trackIndexes, double[] holds, int fromBar, int bars)
            throws Exception {
        Sequence seq = MidiSystem.getSequence(Path.of(path).toFile());
        if (seq.getDivisionType() != Sequence.PPQ) throw new IllegalArgumentException("tylko PPQ");
        int ppq = seq.getResolution();

        for (var t : seq.getTracks())
            for (int i = 0; i < t.size(); i++)
                if (t.get(i).getMessage() instanceof MetaMessage m && m.getType() == 0x51) {
                    byte[] d = m.getData();
                    bpm = 60_000_000.0 / (((d[0] & 0xff) << 16) | ((d[1] & 0xff) << 8) | (d[2] & 0xff));
                    i = t.size();
                }

        double from = fromBar * BAR_BEATS, len = bars * BAR_BEATS;
        double secPerBeat = 60.0 / bpm;
        Map<String, Sample> cache = new HashMap<>();
        List<MNote> notes = new ArrayList<>();

        for (int layer = 0; layer < trackIndexes.length; layer++) {
            double hold = holds[Math.min(layer, holds.length - 1)];
            var track = seq.getTracks()[trackIndexes[layer]];
            Map<Integer, Deque<long[]>> playing = new HashMap<>();

            for (int i = 0; i < track.size(); i++) {
                MidiEvent e = track.get(i);
                if (!(e.getMessage() instanceof ShortMessage sm)) continue;
                int pitch = sm.getData1();
                boolean on = sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0;
                boolean off = sm.getCommand() == ShortMessage.NOTE_OFF
                        || (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() == 0);
                if (on) {
                    playing.computeIfAbsent(pitch, k -> new ArrayDeque<>())
                            .addLast(new long[]{e.getTick(), sm.getData2()});
                } else if (off) {
                    Deque<long[]> q = playing.get(pitch);
                    if (q == null || q.isEmpty()) continue;
                    long[] start = q.removeFirst();
                    double beat = start[0] / (double) ppq;
                    double dur = (e.getTick() - start[0]) / (double) ppq;
                    if (dur <= 0 || beat < from || beat >= from + len) continue;
                    double held = hold > 0 ? hold : dur * secPerBeat;
                    String key = pitch + "@" + Math.round(held * 1000);
                    Sample s = cache.computeIfAbsent(key, k -> lead(pitch, held));
                    notes.add(new MNote(beat - from, s, start[1] / 127f));
                }
            }
        }

        notes.sort(Comparator.comparingDouble(MNote::beat));
        melody = List.copyOf(notes);
        melodyBeats = len;
    }

    // ------------------------------------------------------------ petla audio

    public static void main(String[] args) throws Exception {
        Sample bd = drum("bd", Beat::kick), sd = drum("sd", Beat::snare),
               hh = drum("hh", () -> hat(0.055, 90)), oh = drum("oh", () -> hat(0.30, 13)),
               cp = drum("cp", Beat::clap);

        tracks.set(List.of(
            new Track(bd, "X..-..X...-.X...", 1.00f),
            new Track(sd, "....X.......X...", 0.70f),
            new Track(hh, "x.x.x.x.x.x.x.x.", 0.35f),
            new Track(oh, "..o.....", 0.25f),          // 8 krokow -> polimetria
            new Track(cp, "................", 0.60f)
        ));

        System.out.println("perkusja z samples/: " + (sampled.isEmpty() ? "brak" : String.join(", ", sampled)));

        boolean toFile = args.length > 0 && args[0].equals("--wav");
        String path = toFile && args.length > 1 ? args[1] : "beat.wav";
        int bars = toFile && args.length > 2 ? Integer.parseInt(args[2]) : 8;

        String mid = System.getProperty("mid", "src/main/resources/shape.mid");
        String melTracks = System.getProperty("mid.track", "1");
        if (Files.exists(Path.of(mid))) {
            loadMelody(mid,
                    Arrays.stream(melTracks.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray(),
                    holds(System.getProperty("mel.hold", "0")),
                    Integer.getInteger("mid.fromBar", 0),
                    Integer.getInteger("mid.bars", 4));
            System.out.printf("melodia: %s sciezki %s, %d nut, petla %.0f taktow, %.1f BPM%n",
                    Path.of(mid).getFileName(), melTracks, melody.size(),
                    melodyBeats / BAR_BEATS, bpm);
        }

        // wszystko prealokowane - zero smieci w petli audio
        float[] mix = new float[BLOCK * 2];
        byte[]  out = new byte[BLOCK * 4];
        Voice[] pool = new Voice[MAX_VOICES];
        for (int i = 0; i < pool.length; i++) pool[i] = new Voice();

        AudioFormat fmt = new AudioFormat(SR, 16, 2, true, false);
        long pos = 0;

        if (toFile) {
            long total = Math.round(bars * BAR_BEATS * 60.0 / bpm * SR);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while (pos < total) {
                Arrays.fill(mix, 0f);
                schedule(pos, pool);
                scheduleMelody(pos, pool);
                for (Voice v : pool) if (v.active) v.render(mix, pos);
                encode(mix, out);
                buf.write(out);
                pos += BLOCK;
            }
            byte[] pcm = buf.toByteArray();
            AudioSystem.write(new AudioInputStream(
                    new ByteArrayInputStream(pcm), fmt, pcm.length / 4),
                    AudioFileFormat.Type.WAVE, new File(path));
            System.out.printf("zapisano %s (%d taktow, %.1f BPM)%n", path, bars, bpm);
            return;
        }

        SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
        line.open(fmt, BLOCK * 4 * 4);   // ~46 ms zapasu
        line.start();
        console();
        System.out.println("gram @ " + bpm + " BPM   (help = pomoc, quit = koniec)");

        while (running) {
            Arrays.fill(mix, 0f);
            schedule(pos, pool);
            scheduleMelody(pos, pool);
            for (Voice v : pool) if (v.active) v.render(mix, pos);
            encode(mix, out);
            line.write(out, 0, out.length);   // <- zegar
            pos += BLOCK;
        }
        line.drain();
        line.close();
    }

    static void encode(float[] mix, byte[] out) {
        for (int i = 0; i < mix.length; i++) {
            int s = Math.round(Math.max(-1f, Math.min(1f, mix[i])) * 32767);
            out[i * 2]     = (byte) (s & 0xff);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
    }

    // --------------------------------------------------------- live control

    static volatile boolean running = true;

    static void console() {
        Thread t = new Thread(() -> {
            var in = new BufferedReader(new InputStreamReader(System.in));
            String l;
            try {
                while ((l = in.readLine()) != null) {
                    String[] p = l.trim().split("\\s+");
                    if (p[0].isEmpty()) continue;
                    switch (p[0]) {
                        case "quit" -> { running = false; return; }
                        case "bpm"  -> bpm = Double.parseDouble(p[1]);
                        case "lead" -> melodyGain = Float.parseFloat(p[1]);
                        case "help" -> System.out.println("""
                            bd|sd|hh|oh|cp <wzorzec>   x=akcent X=mocny o=cicho .=pauza
                            bpm <liczba>
                            lead <glosnosc>            0 = melodia cicho
                            quit""");
                        default -> {
                            for (Track tr : tracks.get())
                                if (tr.sample.name().equals(p[0])) tr.steps = p[1];
                        }
                    }
                }
            } catch (Exception e) { running = false; }
        });
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------- synteza

    static final Random RND = new Random(42);

    static final List<String> sampled = new ArrayList<>();

    /** samples/<nazwa>.wav jesli jest, inaczej synteza. */
    static Sample drum(String name, Supplier<Sample> fallback) {
        Path wav = Path.of("samples", name + ".wav");
        if (Files.exists(wav)) {
            try {
                Sample s = new Sample(name, WavSampleLoader.load(wav, SR).copyMono());
                sampled.add(name);
                return s;
            } catch (Exception e) {
                System.out.println(wav + ": " + e.getMessage() + " - uzywam syntezy");
            }
        }
        return fallback.get();
    }

    /** render() sam dokleja ogon zwolnienia za podanym frameCount. */
    static final PitchSynth SYNTH = switch (System.getProperty("synth", "anthem")) {
        case "anthem" -> new AnthemLeadSynth();
        case "pad" -> new WidePadSynth();
        default -> new TrancePluckSynth();
    };

    static Sample lead(int midiNote, double seconds) {
        return new Sample("lead", SYNTH.render(midiNote, (int) (seconds * SR), SR).copyMono());
    }

    static Sample kick() {
        int n = (int) (0.40 * SR);
        float[] d = new float[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f = 45 + 130 * Math.exp(-t * 38);
            ph += 2 * Math.PI * f / SR;
            double a = Math.exp(-t * 7.5);
            d[i] = (float) (Math.sin(ph) * a * 0.95 + Math.exp(-t * 900) * 0.25);
        }
        return new Sample("bd", d);
    }

    static Sample snare() {
        int n = (int) (0.22 * SR);
        float[] d = new float[n];
        double hp = 0, prev = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double x = RND.nextDouble() * 2 - 1;
            hp = 0.72 * (hp + x - prev); prev = x;
            double tone = Math.sin(2 * Math.PI * 185 * t) * Math.exp(-t * 34);
            d[i] = (float) ((hp * Math.exp(-t * 24) * 0.8 + tone * 0.45) * 0.9);
        }
        return new Sample("sd", d);
    }

    static Sample hat(double len, double decay) {
        int n = (int) (len * SR);
        float[] d = new float[n];
        double hp = 0, prev = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double x = RND.nextDouble() * 2 - 1;
            hp = 0.90 * (hp + x - prev); prev = x;
            d[i] = (float) (hp * Math.exp(-t * decay) * 0.6);
        }
        return new Sample(decay > 40 ? "hh" : "oh", d);
    }

    static Sample clap() {
        int n = (int) (0.28 * SR);
        float[] d = new float[n];
        double hp = 0, prev = 0;
        int[] bursts = {0, (int) (0.010 * SR), (int) (0.021 * SR)};
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double x = RND.nextDouble() * 2 - 1;
            hp = 0.85 * (hp + x - prev); prev = x;
            double env = Math.exp(-t * 11) * 0.35;
            for (int b : bursts) if (i >= b) env += Math.exp(-((double) (i - b) / SR) * 160) * 0.6;
            d[i] = (float) (hp * env * 0.55);
        }
        return new Sample("cp", d);
    }
}
