package voidbeat;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Real-time software mixer feeding a single low-latency SourceDataLine.
 * Mixes the current song (gameplay or looping preview) with hitsound voices, and exposes a
 * smoothed, latency-compensated song clock. If no audio device exists it keeps running on the
 * system clock so the game is still playable (silently).
 */
final class AudioEngine {
    static final int SR = 44100;
    private static final int CHUNK = 256;

    private SourceDataLine line;
    private boolean hasLine;
    private int lineBufFrames;
    private final Thread thread;
    private volatile boolean running = true;

    // shared state (guarded by 'this' for multi-field changes)
    private short[] track;
    private long pos;              // frame position in track (may be negative = lead-in silence)
    private boolean playing;
    private boolean preview;
    private long pvStart, pvEnd;
    private double fade = 1, fadeTarget = 1;
    private int gen;               // bumps on every track change so stale clock snapshots are dropped

    volatile float musicVol = 0.8f, sfxVol = 0.55f;

    // clock snapshot written by mixer thread
    private volatile long snapPos;
    private volatile long snapNanos;
    private volatile boolean snapPlaying;

    // smoothed clock (main thread)
    private double smooth;
    private long lastSmoothNanos;
    private boolean smoothInit;

    private final ConcurrentLinkedQueue<Voice> incoming = new ConcurrentLinkedQueue<>();
    private final ArrayList<Voice> voices = new ArrayList<>();

    private static final class Voice {
        final float[] data; int idx; final float gain; final float pan;
        Voice(float[] d, float g, float p) { data = d; gain = g; pan = p; }
    }

    final float[] hitSound = makeHitSound();
    final float[] holdEndSound = makeSoftTick();
    final float[] uiSound = makeUiBlip();
    final float[] missSound = makeMissSound();

    AudioEngine() {
        try {
            AudioFormat fmt = new AudioFormat(SR, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, 2048 * 4);
            line.start();
            lineBufFrames = line.getBufferSize() / 4;
            hasLine = true;
        } catch (Throwable t) {
            hasLine = false;
            System.err.println("[voidbeat] No audio output available (" + t.getMessage() + ") - running silent.");
        }
        thread = new Thread(this::mixLoop, "voidbeat-mixer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    boolean hasAudio() { return hasLine; }

    int outputLatencyMs() { return hasLine ? (int) (lineBufFrames * 1000L / SR) : 0; }

    // ------------------------------------------------------------------ control
    synchronized void playSong(short[] audio, double startSec) {
        track = audio;
        pos = (long) Math.floor(startSec * SR);
        playing = true;
        preview = false;
        fade = fadeTarget = 1;
        smoothInit = false;
        gen++;
        snapPos = pos; snapNanos = System.nanoTime(); snapPlaying = true;
    }

    synchronized void playPreview(short[] audio, double fromSec, double lenSec) {
        if (audio == null) return;
        if (preview && track == audio) return;
        track = audio;
        long total = audio.length / 2;
        pvStart = Math.max(0, Math.min(total - SR, (long) (fromSec * SR)));
        pvEnd = Math.min(total, pvStart + (long) (lenSec * SR));
        pos = pvStart;
        playing = true;
        preview = true;
        fade = 0; fadeTarget = 1;
        smoothInit = false;
        gen++;
        snapPos = pos; snapNanos = System.nanoTime(); snapPlaying = true;
    }

    synchronized void stop() { playing = false; track = null; preview = false; gen++; }

    synchronized void setPaused(boolean p) {
        if (track == null) return;
        playing = !p;
        smoothInit = false;
    }

    synchronized boolean isPlaying() { return playing && track != null; }

    synchronized boolean isPreview() { return preview; }

    void play(float[] snd, float gain, float pan) { incoming.add(new Voice(snd, gain, pan)); }

    // ------------------------------------------------------------------ clock
    /** Raw song position in ms from the mixer's latest snapshot, extrapolated by wall time. */
    private double rawMs() {
        long p = snapPos, nanos = snapNanos;
        double ms = p * 1000.0 / SR;
        if (snapPlaying) ms += (System.nanoTime() - nanos) / 1e6;
        return ms;
    }

    /** Call once per frame. Returns smoothed song time in ms. */
    double updateClock() {
        long now = System.nanoTime();
        double raw = rawMs();
        if (!smoothInit) {
            smooth = raw; smoothInit = true;
        } else {
            double dt = (now - lastSmoothNanos) / 1e6;
            if (snapPlaying) smooth += dt;
            double drift = raw - smooth;
            if (Math.abs(drift) > 45) smooth = raw;
            else smooth += drift * 0.08;
        }
        lastSmoothNanos = now;
        return smooth;
    }

    /** Clock value right now (between frames), for input timestamps. */
    double nowMs() {
        if (!smoothInit) return rawMs();
        return smooth + (snapPlaying ? (System.nanoTime() - lastSmoothNanos) / 1e6 : 0);
    }

    // ------------------------------------------------------------------ spectrum for visuals
    /** Copies a mono window of the currently heard audio into dst (zeros if nothing plays). */
    void window(float[] dst) {
        short[] tr;
        long p;
        synchronized (this) { tr = track; p = (long) (nowMs() * SR / 1000.0); }
        if (tr == null) { java.util.Arrays.fill(dst, 0); return; }
        long total = tr.length / 2;
        long s0 = p - dst.length / 2;
        for (int i = 0; i < dst.length; i++) {
            long k = s0 + i;
            dst[i] = (k >= 0 && k < total) ? (tr[(int) (2 * k)] + tr[(int) (2 * k + 1)]) / 65536f : 0;
        }
    }

    // ------------------------------------------------------------------ mixer
    private void mixLoop() {
        float[] L = new float[CHUNK], R = new float[CHUNK];
        byte[] out = new byte[CHUNK * 4];
        long simNanos = System.nanoTime();
        while (running) {
            java.util.Arrays.fill(L, 0);
            java.util.Arrays.fill(R, 0);
            Voice nv;
            while ((nv = incoming.poll()) != null) voices.add(nv);

            long endPos;
            boolean pl;
            int myGen;
            synchronized (this) {
                myGen = gen;
                pl = playing && track != null;
                if (pl) {
                    long total = track.length / 2;
                    float mv = musicVol;
                    for (int i = 0; i < CHUNK; i++) {
                        if (preview) {
                            fade += (fadeTarget - fade) * 0.0008;
                            double edge = Math.min(1, Math.min((pos - pvStart) / (SR * 0.6), (pvEnd - pos) / (SR * 1.5)));
                            double g = fade * Math.max(0, edge) * mv;
                            if (pos >= pvEnd) { pos = pvStart; fade = 0; }
                            int k = (int) pos * 2;
                            L[i] += tr(k) * g; R[i] += tr(k + 1) * g;
                        } else if (pos >= 0 && pos < total) {
                            int k = (int) pos * 2;
                            L[i] += tr(k) * mv; R[i] += tr(k + 1) * mv;
                        }
                        pos++;
                    }
                }
                endPos = pos;
            }

            float sv = sfxVol;
            for (int v = voices.size() - 1; v >= 0; v--) {
                Voice vo = voices.get(v);
                float gl = (float) Math.cos((vo.pan + 1) * Math.PI / 4) * 1.41f * vo.gain * sv;
                float gr = (float) Math.sin((vo.pan + 1) * Math.PI / 4) * 1.41f * vo.gain * sv;
                int n = Math.min(CHUNK, vo.data.length - vo.idx);
                for (int i = 0; i < n; i++) {
                    float s = vo.data[vo.idx + i];
                    L[i] += s * gl; R[i] += s * gr;
                }
                vo.idx += n;
                if (vo.idx >= vo.data.length) voices.remove(v);
            }

            for (int i = 0; i < CHUNK; i++) {
                int l = (int) (softClip(L[i]) * 32767), r = (int) (softClip(R[i]) * 32767);
                out[4 * i] = (byte) l; out[4 * i + 1] = (byte) (l >> 8);
                out[4 * i + 2] = (byte) r; out[4 * i + 3] = (byte) (r >> 8);
            }

            long heard = endPos;
            if (hasLine) {
                try {
                    line.write(out, 0, out.length);
                    int queued = lineBufFrames - line.available() / 4;
                    heard = endPos - Math.max(0, queued);
                } catch (Throwable t) {
                    hasLine = false;
                }
            } else {
                simNanos += CHUNK * 1_000_000_000L / SR;
                long wait = simNanos - System.nanoTime();
                if (wait > 0) {
                    try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); } catch (InterruptedException ignored) { }
                } else if (wait < -100_000_000L) simNanos = System.nanoTime();
            }
            synchronized (this) {
                if (myGen == gen) {
                    snapPos = heard;
                    snapNanos = System.nanoTime();
                    snapPlaying = pl;
                }
            }
        }
    }

    private float tr(int k) {
        return (k >= 0 && k + 1 < track.length) ? track[k] / 32768f : 0;
    }

    private static float softClip(float x) {
        if (x > 1.5f) return 1f;
        if (x < -1.5f) return -1f;
        if (Math.abs(x) < 0.8f) return x;
        return (float) (Math.signum(x) * (0.8 + 0.2 * Math.tanh((Math.abs(x) - 0.8) / 0.2)));
    }

    void shutdown() {
        running = false;
        if (hasLine) { line.stop(); line.close(); }
    }

    // ------------------------------------------------------------------ sfx synthesis
    private static float[] makeHitSound() {
        int n = (int) (0.09 * SR);
        float[] d = new float[n];
        Synth.SVF bp = new Synth.SVF().set(3200, 1.6);
        long s = 12345;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            double nz = ((s >>> 11) * (1.0 / (1L << 53))) * 2 - 1;
            bp.process(nz);
            double click = bp.band * Math.exp(-t * 160) * 0.9;
            double body = Math.sin(Synth.TAU * 1250 * t) * Math.exp(-t * 70) * 0.35
                    + Math.sin(Synth.TAU * 2500 * t) * Math.exp(-t * 110) * 0.15;
            d[i] = (float) ((click + body) * 0.55 * Math.min(1, t / 0.0005));
        }
        return d;
    }

    private static float[] makeSoftTick() {
        int n = (int) (0.12 * SR);
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            d[i] = (float) ((Math.sin(Synth.TAU * 1760 * t) * 0.5 + Math.sin(Synth.TAU * 2637 * t) * 0.3)
                    * Math.exp(-t * 35) * 0.25);
        }
        return d;
    }

    private static float[] makeUiBlip() {
        int n = (int) (0.07 * SR);
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            double f = 900 + 600 * Math.exp(-t * 40);
            d[i] = (float) (Math.sin(Synth.TAU * f * t) * Math.exp(-t * 45) * 0.22);
        }
        return d;
    }

    private static float[] makeMissSound() {
        int n = (int) (0.16 * SR);
        float[] d = new float[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / SR;
            ph += Synth.TAU * (220 - 120 * t / 0.16) / SR;
            d[i] = (float) (Math.tanh(Math.sin(ph) * 2) * Math.exp(-t * 22) * 0.16);
        }
        return d;
    }
}
