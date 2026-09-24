package pl.livecoding.musicjam.studio;

import pl.livecoding.musicjam.BeatApp;
import pl.livecoding.musicjam.studio.knobs.SynthControls;
import pl.livecoding.musicjam.synth.LiveNovasawSynth;
import pl.livecoding.musicjam.synth.NovasawSynth;
import pl.livecoding.musicjam.synth.PitchSynth;

/**
 * What one melody track is played by: a synth of its own, and the synth panel as it was last set
 * for it, so that selecting the track puts its knobs back where they were. A ported patch becomes
 * a {@link LiveNovasawSynth}, whose knobs reach the notes already sounding; any other synth plays
 * as it is, with the panel switched off.
 *
 * <p>A track can go out to an external MIDI synth instead, on a channel of its own, with the
 * filter that synth follows as a control change.
 *
 * <p>An instrument is known by its identity, as the engine knows the synth in it: two tracks made
 * from the same patch are two instruments, with two sets of knobs and two delays.
 */
final class Instrument {
    private final PitchSynth synth;
    private final LiveNovasawSynth live;
    private SynthControls.Setting setting;
    // an external synth instead: whether the track goes there, and on which channel with which filter
    private boolean external;
    private int channel;
    private int controller = 74;
    private int filter = 64;

    private Instrument(PitchSynth synth, LiveNovasawSynth live, SynthControls.Setting setting) {
        this.synth = synth;
        this.live = live;
        this.setting = setting;
    }

    /** A fresh instrument from the patch {@code name} names: "pluck", "anthem" and so on. */
    static Instrument of(String name) {
        PitchSynth named = BeatApp.resolveSynth(name);
        if (named instanceof NovasawSynth patch) {
            LiveNovasawSynth live = new LiveNovasawSynth(patch);
            return new Instrument(live, live, SynthControls.Setting.initial(name, live.params()));
        }
        return new Instrument(named, null, null);
    }

    PitchSynth synth() {
        return synth;
    }

    /** Whether the synth panel can play it: a ported patch, rather than a synth from outside them. */
    boolean playable() {
        return live != null;
    }

    SynthControls.Setting setting() {
        return setting;
    }

    /** Whether the track goes out to the external synth rather than being played here. */
    boolean external() {
        return external;
    }

    void setExternal(boolean next) {
        external = next;
    }

    /** The MIDI channel the track goes out on, counting from 0 as the messages do. */
    int channel() {
        return channel;
    }

    /** The control change the external synth's filter follows, CC 74 unless told otherwise. */
    int controller() {
        return controller;
    }

    int filter() {
        return filter;
    }

    /** Where the track goes out, and the filter it is sent there with. */
    void setMidi(int nextChannel, int nextController, int nextFilter) {
        channel = nextChannel;
        controller = nextController;
        filter = nextFilter;
    }

    /** The panel turned for this instrument: the synth hears it now, and the panel will show it again. */
    void set(SynthControls.Setting next) {
        if (live == null) {
            return;
        }
        setting = next;
        live.setParams(next.params());
        live.setEffectParams(next.effects());
    }
}
