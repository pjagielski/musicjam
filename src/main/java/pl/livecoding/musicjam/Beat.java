package pl.livecoding.musicjam;

import javax.sound.sampled.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Beat.java - minimalna maszyna perkusyjna oparta o render blokowy.
 *
 *   java Beat.java                  -> gra na zywo (sterowanie z stdin)
 *   java Beat.java --wav out.wav 8  -> renderuje 8 taktow do pliku
 *
 * Zegarem jest line.write() - blokuje, gdy bufor karty jest pelny.
 * Zdarzenia startuja z offsetem WEWNATRZ bloku, nie na jego granicy.
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

    static volatile double bpm = 124;
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

    // ------------------------------------------------------------ petla audio

    public static void main(String[] args) throws Exception {
        Sample bd = kick(), sd = snare(), hh = hat(0.055, 90), oh = hat(0.30, 13), cp = clap();

        tracks.set(List.of(
            new Track(bd, "X..-..X...-.X...", 1.00f),
            new Track(sd, "....X.......X...", 0.70f),
            new Track(hh, "x.x.x.x.x.x.x.x.", 0.35f),
            new Track(oh, "..o.....", 0.25f),          // 8 krokow -> polimetria
            new Track(cp, "................", 0.60f)
        ));

        boolean toFile = args.length > 0 && args[0].equals("--wav");
        String path = toFile && args.length > 1 ? args[1] : "beat.wav";
        int bars = toFile && args.length > 2 ? Integer.parseInt(args[2]) : 8;

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
                for (Voice v : pool) if (v.active) v.render(mix, pos);
                encode(mix, out);
                buf.write(out);
                pos += BLOCK;
            }
            byte[] pcm = buf.toByteArray();
            AudioSystem.write(new AudioInputStream(
                    new ByteArrayInputStream(pcm), fmt, pcm.length / 4),
                    AudioFileFormat.Type.WAVE, new File(path));
            System.out.println("zapisano " + path + " (" + bars + " taktow, " + bpm + " BPM)");
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
                        case "help" -> System.out.println("""
                            bd|sd|hh|oh|cp <wzorzec>   x=akcent X=mocny o=cicho .=pauza
                            bpm <liczba>
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
