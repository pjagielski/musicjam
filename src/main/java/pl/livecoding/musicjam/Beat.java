package pl.livecoding.musicjam;

import pl.livecoding.musicjam.audio.WavSampleLoader;
import pl.livecoding.musicjam.synth.AnthemLeadSynth;

import javax.sound.midi.*;
import javax.sound.sampled.*;
import java.nio.file.*;
import java.util.*;

/**
 * Beat.java - maszyna perkusyjna oparta o render blokowy, plus melodia z pliku MIDI.
 * .\gradlew.bat beat, Ctrl+C konczy (java Beat.java wymaga JDK 22+).
 *
 * Zegarem jest line.write() - blokuje, gdy bufor karty jest pelny.
 * Zdarzenia startuja z offsetem WEWNATRZ bloku, nie na jego granicy.
 */
public class Beat {

    static final int SR = 44100;      // sample rate
    static final int BLOCK = 512;     // ramek na blok (~11.6 ms)
    static final int MAX_VOICES = 32;
    static final double BAR_BEATS = 4.0;

    /** Sciezka: sampel + wzorzec krokow. Dlugosc stringa = liczba krokow na takt. */
    static final class Track {
        final float[] sample;
        final String steps;
        final float gain;
        long nextStep = 0;            // absolutny indeks kroku (nigdy nie resetowany)

        Track(float[] sample, String steps, float gain) {
            this.sample = sample; this.steps = steps; this.gain = gain;
        }
    }

    /** Glos: jedno odtwarzanie sampla (mono, float [-1, 1]) zaczynajace sie w konkretnej ramce. */
    static final class Voice {
        float[] sample;
        long startFrame;
        float gain;
        boolean active;

        void trigger(float[] s, long startFrame, float gain) {
            this.sample = s; this.startFrame = startFrame; this.gain = gain; this.active = true;
        }

        /** Miksuje sie do bufora. blockStart = absolutna ramka poczatku bloku. */
        void render(float[] mix, long blockStart) {
            // offset wewnatrz bloku - TO jest cala precyzja
            int from = (int) Math.max(0, startFrame - blockStart);
            long idx = blockStart + from - startFrame;
            for (int i = from; i < BLOCK; i++, idx++) {
                if (idx >= sample.length) { active = false; return; }
                float v = sample[(int) idx] * gain;
                mix[i * 2]     += v;
                mix[i * 2 + 1] += v;
            }
        }
    }

    static double bpm = 96;
    static List<Track> tracks;

    /** Absolutna ramka betu - liczona zawsze od zera, nigdy przyrostowo. */
    static long frameAt(double beat) {
        return Math.round(beat * 60.0 / bpm * SR);
    }

    /** Odpala wszystkie kroki wpadajace w [pos, pos+BLOCK). */
    static void schedule(long pos, Voice[] pool) {
        for (Track t : tracks) {
            int n = t.steps.length();
            while (frameAt(t.nextStep * BAR_BEATS / n) < pos + BLOCK) {
                long f = frameAt(t.nextStep * BAR_BEATS / n);
                if (f >= pos) {
                    char c = t.steps.charAt((int) (t.nextStep % n));
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

    /** Beat liczony wzgledem poczatku petli. */
    record MNote(double beat, float[] sample, float gain) {}

    static List<MNote> melody;
    static double melodyBeats;
    static int melNext = 0;
    static long melLoop = 0;

    static void scheduleMelody(long pos, Voice[] pool) {
        while (frameAt(melLoop * melodyBeats + melody.get(melNext).beat()) < pos + BLOCK) {
            MNote n = melody.get(melNext);
            long f = frameAt(melLoop * melodyBeats + n.beat());
            if (f >= pos) alloc(pool).trigger(n.sample(), f, n.gain() * 0.45f);
            if (++melNext == melody.size()) { melNext = 0; melLoop++; }
        }
    }

    /** Tempo bierzemy z pliku - melodia i perkusja maja isc w jednym tempie. */
    static void loadMelody(String path, int trackIndex, int fromBar, int bars) throws Exception {
        Sequence seq = MidiSystem.getSequence(Path.of(path).toFile());
        int ppq = seq.getResolution();
        for (var t : seq.getTracks())
            for (int i = 0; i < t.size(); i++)
                if (t.get(i).getMessage() instanceof MetaMessage m && m.getType() == 0x51) {
                    byte[] d = m.getData();
                    bpm = 60_000_000.0 / (((d[0] & 0xff) << 16) | ((d[1] & 0xff) << 8) | (d[2] & 0xff));
                    i = t.size();
                }

        double from = fromBar * BAR_BEATS, len = bars * BAR_BEATS, secPerBeat = 60.0 / bpm;
        var synth = new AnthemLeadSynth();   // render() sam dokleja ogon zwolnienia
        Map<String, float[]> cache = new HashMap<>();
        Map<Integer, Deque<long[]>> playing = new HashMap<>();
        List<MNote> notes = new ArrayList<>();

        var track = seq.getTracks()[trackIndex];
        for (int i = 0; i < track.size(); i++) {
            MidiEvent e = track.get(i);
            if (!(e.getMessage() instanceof ShortMessage sm)) continue;
            int cmd = sm.getCommand(), pitch = sm.getData1(), vel = sm.getData2();
            if (cmd == ShortMessage.NOTE_ON && vel > 0) {
                playing.computeIfAbsent(pitch, k -> new ArrayDeque<>()).addLast(new long[]{e.getTick(), vel});
            } else if (cmd == ShortMessage.NOTE_OFF || cmd == ShortMessage.NOTE_ON) {
                Deque<long[]> q = playing.get(pitch);
                if (q == null || q.isEmpty()) continue;
                long[] start = q.removeFirst();
                double beat = start[0] / (double) ppq, dur = (e.getTick() - start[0]) / (double) ppq;
                if (dur <= 0 || beat < from || beat >= from + len) continue;
                double seconds = dur * secPerBeat;
                float[] s = cache.computeIfAbsent(pitch + "@" + Math.round(seconds * 1000),
                        k -> synth.render(pitch, (int) (seconds * SR), SR).copyMono());
                notes.add(new MNote(beat - from, s, start[1] / 127f));
            }
        }

        notes.sort(Comparator.comparingDouble(MNote::beat));
        melody = List.copyOf(notes);
        melodyBeats = len;
    }

    public static void main(String[] args) throws Exception {
        tracks = List.of(
            new Track(sample("bd"), "X..-..X...-.X...", 1.00f),
            new Track(sample("sd"), "....X.......X...", 0.70f),
            new Track(sample("hh"), "x.x.x.x.x.x.x.x.", 0.35f),
            new Track(openHat(),    "..o.....", 0.25f)          // 8 krokow -> polimetria
        );
        loadMelody("src/main/resources/song_shape.mid", 1, 0, 4);

        // wszystko prealokowane - zero smieci w petli audio
        float[] mix = new float[BLOCK * 2];
        byte[]  out = new byte[BLOCK * 4];
        Voice[] pool = new Voice[MAX_VOICES];
        for (int i = 0; i < pool.length; i++) pool[i] = new Voice();

        AudioFormat fmt = new AudioFormat(SR, 16, 2, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
        line.open(fmt, BLOCK * 4 * 4);   // ~46 ms zapasu
        line.start();
        System.out.println("gram @ " + bpm + " BPM, Ctrl+C konczy");
        for (long pos = 0; ; pos += BLOCK) {
            Arrays.fill(mix, 0f);
            schedule(pos, pool);
            scheduleMelody(pos, pool);
            for (Voice v : pool) if (v.active) v.render(mix, pos);
            encode(mix, out);
            line.write(out, 0, out.length);   // <- zegar
        }
    }

    static void encode(float[] mix, byte[] out) {
        for (int i = 0; i < mix.length; i++) {
            int s = Math.round(Math.max(-1f, Math.min(1f, mix[i])) * 32767);
            out[i * 2]     = (byte) (s & 0xff);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
    }

    static float[] sample(String name) throws Exception {
        return WavSampleLoader.load(Path.of("samples", name + ".wav"), SR).copyMono();
    }

    /** Otwartego hi-hatu nie ma w samples/, wiec jest syntezowany. */
    static float[] openHat() {
        Random rnd = new Random(42);
        float[] d = new float[(int) (0.30 * SR)];
        double hp = 0, prev = 0;
        for (int i = 0; i < d.length; i++) {
            double x = rnd.nextDouble() * 2 - 1;
            hp = 0.90 * (hp + x - prev); prev = x;
            d[i] = (float) (hp * Math.exp(-((double) i / SR) * 13) * 0.6);
        }
        return d;
    }
}
