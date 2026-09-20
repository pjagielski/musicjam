package pl.livecoding.musicjam.synth;

import pl.livecoding.musicjam.audio.AudioEffect;

import java.util.function.Supplier;

/**
 * The synth channel's effects in stereo: a ping-pong delay with a damped feedback path, then a
 * Freeverb-style reverb.
 *
 * <p>The reverb follows Jezar's Freeverb (public domain, June 2000) as described in Julius O.
 * Smith's <i>Physical Audio Signal Processing</i>, §3.6: eight lowpass-feedback comb filters in
 * parallel into four allpass sections in series, per channel, with the right channel's twelve delay
 * lines lengthened by {@code STEREO_SPREAD} so the two ears never hear the same room. The tunings
 * below are Freeverb's own, scaled from its 44.1 kHz assumption to whatever rate we run at:
 * {@code damping = damp * 0.4}, {@code feedback = size * 0.28 + 0.7}, allpass {@code g = 0.5}.
 *
 * <p>The delay has four ways of using its two lines — see {@link DelayMode}. Whichever it is, each
 * trip round the loop goes through a one-pole lowpass (the Tone knob) and a soft clip, which is what
 * keeps a high feedback setting from either turning into a bright screech or running away.
 *
 * <p>Like the voices, everything is read from {@link EffectParams} on every frame, so a knob is
 * heard in the tail that is already ringing.
 */
public final class SynthEffects implements AudioEffect {

    private static final int[] COMB_FRAMES = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASS_FRAMES = {556, 441, 341, 225};
    private static final int STEREO_SPREAD = 23;
    private static final float FIXED_GAIN = 0.015f;
    private static final float WET_SCALE = 3.0f;
    private static final float DAMP_SCALE = 0.4f;
    private static final float ROOM_SCALE = 0.28f;
    private static final float ROOM_OFFSET = 0.7f;
    private static final float ALLPASS_FEEDBACK = 0.5f;

    private static final int CHANNELS = 2;
    private static final float TIME_SMOOTHING = 0.0005f;
    private static final float HIGHPASS_COEFFICIENT = 0.995f;

    private final Supplier<EffectParams> params;
    private final int sampleRate;

    private final float[][] delayLines = new float[CHANNELS][];
    private final int[] delayPositions = new int[CHANNELS];
    private final float[] toneStates = new float[CHANNELS];
    private final float[] highpassStates = new float[CHANNELS];
    private final float[] highpassInputs = new float[CHANNELS];

    private final float[][][] combs = new float[CHANNELS][COMB_FRAMES.length][];
    private final float[][] combStores = new float[CHANNELS][COMB_FRAMES.length];
    private final int[][] combPositions = new int[CHANNELS][COMB_FRAMES.length];
    private final float[][][] allpasses = new float[CHANNELS][ALLPASS_FRAMES.length][];
    private final int[][] allpassPositions = new int[CHANNELS][ALLPASS_FRAMES.length];

    private float delayFrames;
    private double tapePhase;

    public SynthEffects(int sampleRate, Supplier<EffectParams> params) {
        this.sampleRate = sampleRate;
        this.params = params;
        this.delayFrames = params.get().delayMillis() * sampleRate / 1000.0f;
        for (int channel = 0; channel < CHANNELS; channel++) {
            delayLines[channel] = new float[2 * sampleRate + 1];
            int spread = channel * STEREO_SPREAD;
            for (int comb = 0; comb < COMB_FRAMES.length; comb++) {
                combs[channel][comb] = new float[scaled(COMB_FRAMES[comb] + spread)];
            }
            for (int allpass = 0; allpass < ALLPASS_FRAMES.length; allpass++) {
                allpasses[channel][allpass] = new float[scaled(ALLPASS_FRAMES[allpass] + spread)];
            }
        }
    }

    /** Freeverb's lengths are in frames at 44.1 kHz; at another rate the room would change size. */
    private int scaled(int framesAt44k) {
        return Math.max(1, Math.round(framesAt44k * sampleRate / 44_100.0f));
    }

    @Override
    public void process(float input, float[] stereoOut) {
        EffectParams current = params.get();
        delay(input, current, stereoOut);
        for (int channel = 0; channel < CHANNELS; channel++) {
            stereoOut[channel] = reverb(channel, stereoOut[channel], current);
        }
    }

    private void delay(float input, EffectParams current, float[] stereoOut) {
        float target = current.delayMillis() * sampleRate / 1000.0f;
        delayFrames += (target - delayFrames) * TIME_SMOOTHING;
        tapePhase += 2 * Math.PI * DelayMode.TAPE_RATE_HZ / sampleRate;
        if (tapePhase > 2 * Math.PI) {
            tapePhase -= 2 * Math.PI;
        }

        float leftFrames = framesFor(current.delayMode(), 0);
        float rightFrames = framesFor(current.delayMode(), 1);
        float echoLeft = read(0, leftFrames);
        float echoRight = read(1, rightFrames);

        switch (current.delayMode()) {
            // the dry signal enters the left line only and the two lines' feedback is crossed over,
            // so a repeat is heard first on one side and then on the other
            case PING_PONG -> {
                write(0, input + loop(0, echoRight, current));
                write(1, loop(1, echoLeft, current));
            }
            // a line each, feeding itself: same time in MONO and TAPE, two thirds of it in STEREO
            case MONO, STEREO, TAPE -> {
                write(0, input + loop(0, echoLeft, current));
                write(1, input + loop(1, echoRight, current));
            }
        }

        float mix = current.delayMix();
        stereoOut[0] = input * (1 - mix) + echoLeft * mix;
        stereoOut[1] = input * (1 - mix) + echoRight * mix;
    }

    /** How far back each line reads: the same for both, shorter on the right, or drifting. */
    private float framesFor(DelayMode mode, int channel) {
        return switch (mode) {
            case MONO, PING_PONG -> delayFrames;
            case STEREO -> channel == 0 ? delayFrames : delayFrames * DelayMode.STEREO_RATIO;
            // the two sides drift in opposite directions, which widens the repeats as they warble
            case TAPE -> delayFrames * (float) (1 + DelayMode.TAPE_DEPTH
                    * Math.sin(tapePhase + (channel == 0 ? 0 : Math.PI / 2)));
        };
    }

    /** One trip round the feedback loop: quieter, darker, without the rumble, and never louder than 1. */
    private float loop(int channel, float echo, EffectParams current) {
        float fed = echo * current.delayFeedback();
        float cutoff = 0.05f + current.delayTone() * 0.94f;
        toneStates[channel] += (fed - toneStates[channel]) * cutoff;
        float damped = toneStates[channel];
        float highpassed = HIGHPASS_COEFFICIENT * (highpassStates[channel] + damped - highpassInputs[channel]);
        highpassInputs[channel] = damped;
        highpassStates[channel] = highpassed;
        return softClip(highpassed);
    }

    private static float softClip(float value) {
        return (float) Math.tanh(value);
    }

    private float read(int channel, float frames) {
        float[] line = delayLines[channel];
        float read = delayPositions[channel] - Math.min(frames, line.length - 2);
        while (read < 0) {
            read += line.length;
        }
        int first = (int) read;
        float fraction = read - first;
        return line[first] * (1 - fraction) + line[(first + 1) % line.length] * fraction;
    }

    private void write(int channel, float value) {
        float[] line = delayLines[channel];
        line[delayPositions[channel]] = value;
        delayPositions[channel] = (delayPositions[channel] + 1) % line.length;
    }

    /** Freeverb, one channel of it: eight damped combs in parallel, then four allpasses in series. */
    private float reverb(int channel, float input, EffectParams current) {
        float feedback = current.reverbSize() * ROOM_SCALE + ROOM_OFFSET;
        float damping = current.reverbDamping() * DAMP_SCALE;
        float fed = input * FIXED_GAIN;
        float wet = 0.0f;

        for (int comb = 0; comb < COMB_FRAMES.length; comb++) {
            float[] line = combs[channel][comb];
            int position = combPositions[channel][comb];
            float heard = line[position];
            combStores[channel][comb] = heard * (1 - damping) + combStores[channel][comb] * damping;
            line[position] = fed + combStores[channel][comb] * feedback;
            combPositions[channel][comb] = (position + 1) % line.length;
            wet += heard;
        }
        for (int allpass = 0; allpass < ALLPASS_FRAMES.length; allpass++) {
            float[] line = allpasses[channel][allpass];
            int position = allpassPositions[channel][allpass];
            float heard = line[position];
            line[position] = wet + heard * ALLPASS_FEEDBACK;
            allpassPositions[channel][allpass] = (position + 1) % line.length;
            wet = heard - wet;
        }
        return input * (1 - current.reverbMix()) + wet * WET_SCALE * current.reverbMix();
    }
}
