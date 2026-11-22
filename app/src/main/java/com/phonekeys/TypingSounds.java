package com.phonekeys;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Typing sounds, synthesized in code so the app needs no audio files.
 * Each style has a normal key, space, enter and backspace sound.
 */
final class TypingSounds {

    static final String[] STYLE_NAMES = {"Off", "Mechanical", "Typewriter", "Soft", "Bubble", "Clicky"};

    enum Kind { KEY, SPACE, ENTER, BACKSPACE }

    private static final int RATE = 44100;

    private final Context context;
    private final SoundPool pool;
    private final Random random = new Random();
    private int[] ids = new int[0];   // indexed by Kind.ordinal()
    private int style;
    private float volume = 0.8f;

    TypingSounds(Context context) {
        this.context = context.getApplicationContext();
        pool = new SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
    }

    void setVolume(float v) { volume = v; }

    void setStyle(int newStyle) {
        if (newStyle == style && ids.length > 0) return;
        for (int id : ids) pool.unload(id);
        style = newStyle;
        if (style == 0) { ids = new int[0]; return; }
        int[] loaded = new int[Kind.values().length];
        for (Kind k : Kind.values()) {
            loaded[k.ordinal()] = pool.load(writeWav(style + "_" + k.name(), synth(style, k)), 1);
        }
        ids = loaded;
    }

    void play(Kind kind) {
        if (ids.length == 0 || volume <= 0f) return;
        // A little random pitch so fast typing doesn't sound robotic.
        float rate = 0.94f + random.nextFloat() * 0.12f;
        pool.play(ids[kind.ordinal()], volume, volume, 1, 0, rate);
    }

    void release() { pool.release(); }

    // ---------------------------------------------------------------- synthesis

    private float[] synth(int style, Kind k) {
        Random r = new Random(style * 31L + k.ordinal());
        switch (style) {
            case 1: return mechanical(r, k);
            case 2: return typewriter(r, k);
            case 3: return soft(r, k);
            case 4: return bubble(k);
            default: return clicky(r, k);
        }
    }

    /** Thocky mechanical switch: sharp click then a lower "clack" as the key bottoms out. */
    private float[] mechanical(Random r, Kind k) {
        float big = (k == Kind.SPACE || k == Kind.ENTER) ? 1.4f : 1f;
        float[] out = buf(0.09f * big);
        addNoiseBurst(out, r, 0, 0.004f, 0.9f, 0.35f);
        addTone(out, 0.0f, 0.045f * big, 2600, 0.25f, 120);
        addNoiseBurst(out, r, 0.018f * big, 0.010f * big, 0.7f, 0.15f);
        addTone(out, 0.018f * big, 0.06f * big, 180 / big, 0.6f, 60);
        if (k == Kind.BACKSPACE) addTone(out, 0f, 0.05f, 900, 0.2f, 90);
        return out;
    }

    /** Old typewriter: metallic strike; enter rings the bell. */
    private float[] typewriter(Random r, Kind k) {
        if (k == Kind.ENTER) {
            float[] out = buf(0.9f);
            addNoiseBurst(out, r, 0, 0.02f, 0.8f, 0.2f);
            addTone(out, 0.03f, 0.85f, 2093, 0.45f, 5);
            addTone(out, 0.03f, 0.85f, 5230, 0.15f, 9);
            return out;
        }
        float[] out = buf(0.08f);
        addNoiseBurst(out, r, 0, 0.006f, 1f, 0.6f);
        addTone(out, 0, 0.06f, k == Kind.SPACE ? 1400 : 3100, 0.35f, 80);
        addTone(out, 0, 0.07f, k == Kind.SPACE ? 160 : 240, 0.5f, 55);
        return out;
    }

    /** Gentle, muffled tap. */
    private float[] soft(Random r, Kind k) {
        float[] out = buf(0.05f);
        addNoiseBurst(out, r, 0, 0.012f, 0.35f, 0.9f);
        addTone(out, 0, 0.04f, k == Kind.SPACE || k == Kind.ENTER ? 140 : 220, 0.5f, 90);
        return out;
    }

    /** Cute bubble pop that rises in pitch. */
    private float[] bubble(Kind k) {
        float start = 380, end = 950;
        if (k == Kind.SPACE) { start = 300; end = 650; }
        if (k == Kind.ENTER) { start = 500; end = 1400; }
        if (k == Kind.BACKSPACE) { start = 900; end = 400; }
        float dur = 0.07f;
        float[] out = buf(dur);
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            float t = (float) i / out.length;
            double f = start + (end - start) * t;
            phase += 2 * Math.PI * f / RATE;
            float env = (float) (Math.sin(Math.PI * Math.min(1, t * 6)) * Math.exp(-t * 4));
            out[i] += (float) Math.sin(phase) * env * 0.8f;
        }
        return out;
    }

    /** Bright, crisp click like a blue switch. */
    private float[] clicky(Random r, Kind k) {
        float[] out = buf(0.05f);
        addNoiseBurst(out, r, 0, 0.002f, 1f, 0.0f);
        addTone(out, 0, 0.02f, 4200, 0.5f, 250);
        addNoiseBurst(out, r, 0.012f, 0.002f, 0.6f, 0.0f);
        addTone(out, 0.012f, 0.03f, k == Kind.SPACE || k == Kind.ENTER ? 900 : 1800, 0.3f, 150);
        return out;
    }

    private static float[] buf(float seconds) { return new float[(int) (seconds * RATE)]; }

    /** Decaying noise; smoothing (0..1) darkens it with a one-pole low-pass. */
    private static void addNoiseBurst(float[] out, Random r, float start, float len,
                                      float amp, float smoothing) {
        int s = (int) (start * RATE), n = Math.max(1, (int) (len * RATE));
        float prev = 0;
        for (int i = 0; i < n && s + i < out.length; i++) {
            float noise = r.nextFloat() * 2 - 1;
            prev = prev * smoothing + noise * (1 - smoothing);
            float env = (float) Math.exp(-5.0 * i / n);
            out[s + i] += prev * env * amp;
        }
    }

    /** Sine tone with exponential decay (decay = 1/seconds). */
    private static void addTone(float[] out, float start, float len, float freq,
                                float amp, float decay) {
        int s = (int) (start * RATE), n = (int) (len * RATE);
        for (int i = 0; i < n && s + i < out.length; i++) {
            float t = (float) i / RATE;
            float attack = Math.min(1f, i / 40f);
            out[s + i] += (float) (Math.sin(2 * Math.PI * freq * t) * Math.exp(-decay * t)) * amp * attack;
        }
    }

    private String writeWav(String name, float[] samples) {
        // Normalize to avoid clipping, then write 16-bit mono PCM.
        float peak = 0.0001f;
        for (float v : samples) peak = Math.max(peak, Math.abs(v));
        float gain = 0.9f / peak;
        ByteBuffer b = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + samples.length * 2).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(samples.length * 2);
        for (float v : samples) b.putShort((short) (Math.max(-1f, Math.min(1f, v * gain)) * 32767));
        File f = new File(context.getCacheDir(), "snd_" + name + ".wav");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(b.array());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return f.getAbsolutePath();
    }
}
