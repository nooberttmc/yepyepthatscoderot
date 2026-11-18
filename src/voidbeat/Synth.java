package voidbeat;

import java.util.ArrayList;
import java.util.List;

/** Offline software synthesizer: drum machine, bass, supersaw lead, plucks, pads, FX and mixdown. */
final class Synth {
    static final int SR = 44100;
    static final double TAU = Math.PI * 2;

    final int n;
    final float[] dL, dR;      // drums
    final float[] sL, sR;      // synths (lead / pluck / riser) -> delay
    final float[] pL, pR;      // pads
    final float[] bass;        // mono bass (sidechained)
    final float[] sendL, sendR;// extra reverb send
    final List<Double> kicks = new ArrayList<>();
    private long rs = 0x9E3779B97F4A7C15L;

    Synth(double seconds, long seed) {
        n = (int) (seconds * SR);
        dL = new float[n]; dR = new float[n];
        sL = new float[n]; sR = new float[n];
        pL = new float[n]; pR = new float[n];
        bass = new float[n];
        sendL = new float[n]; sendR = new float[n];
        rs ^= seed * 0x2545F4914F6CDD1DL;
        if (rs == 0) rs = 1;
    }

    // ------------------------------------------------------------------ primitives
    double noise() {
        rs ^= rs << 13; rs ^= rs >>> 7; rs ^= rs << 17;
        return ((rs >>> 11) * (1.0 / (1L << 53))) * 2 - 1;
    }

    static double mtof(double m) { return 440.0 * Math.pow(2, (m - 69) / 12.0); }

    private static double blep(double t, double dt) {
        if (t < dt) { t /= dt; return t + t - t * t - 1; }
        if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1; }
        return 0;
    }

    /** Band-limited saw from phase in [0,1). */
    static double saw(double ph, double dt) { return 2 * ph - 1 - blep(ph, dt); }

    /** Zero-delay-feedback state variable filter. */
    static final class SVF {
        double ic1, ic2, a1, a2, a3, k, low, band, high;
        SVF set(double fc, double q) {
            fc = Math.max(20, Math.min(fc, SR * 0.45));
            double g = Math.tan(Math.PI * fc / SR);
            k = 1.0 / q;
            a1 = 1 / (1 + g * (g + k));
            a2 = g * a1;
            a3 = g * a2;
            return this;
        }
        double process(double v0) {
            double v3 = v0 - ic2;
            double v1 = a1 * ic1 + a2 * v3;
            double v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1;
            ic2 = 2 * v2 - ic2;
            low = v2; band = v1; high = v0 - k * v1 - v2;
            return v2;
        }
    }

    private static double panL(double pan) { return Math.cos((pan + 1) * Math.PI / 4); }
    private static double panR(double pan) { return Math.sin((pan + 1) * Math.PI / 4); }

    private int start(double t) { return (int) Math.round(t * SR); }

    // ------------------------------------------------------------------ drums
    void kick(double t, double vel, double punch) {
        int s0 = start(t), len = (int) (0.55 * SR);
        double ph = 0;
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            double f = 44 + (punch > 1 ? 210 : 135) * Math.exp(-tt * (punch > 1 ? 24 : 34));
            ph += TAU * f / SR;
            double env = Math.exp(-tt * (punch > 1 ? 4.2 : 7.5));
            if (tt < 0.0015) env *= tt / 0.0015;
            double v = Math.tanh(Math.sin(ph) * 1.7 * punch) * env + noise() * Math.exp(-tt * 380) * 0.28;
            v *= vel * 0.92;
            if (k >= 0) { dL[k] += v; dR[k] += v; }
        }
        kicks.add(t);
    }

    void snare(double t, double vel) {
        SVF hp = new SVF().set(1700, 0.7);
        int s0 = start(t), len = (int) (0.34 * SR);
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            double tone = (Math.sin(TAU * 185 * tt) * 0.6 + Math.sin(TAU * 330 * tt) * 0.3) * Math.exp(-tt * 30);
            hp.process(noise());
            double nz = hp.high * Math.exp(-tt * 15);
            double v = (tone * 0.7 + nz * 0.95) * vel * 0.5;
            if (k >= 0) { dL[k] += v; dR[k] += v; sendL[k] += v * 0.35; sendR[k] += v * 0.35; }
        }
    }

    void clap(double t, double vel) {
        SVF bp = new SVF().set(1350, 1.3), bp2 = new SVF().set(1250, 1.3);
        int s0 = start(t), len = (int) (0.4 * SR);
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            double env = 0;
            for (int j = 0; j < 3; j++) {
                double d = tt - j * 0.0105;
                if (d >= 0) env += Math.exp(-d * 120) * (j == 2 ? 1 : 0.7);
            }
            if (tt > 0.021) env += Math.exp(-(tt - 0.021) * 12) * 0.5;
            bp.process(noise());
            bp2.process(noise());
            double l = bp.band * env * vel * 0.9, r = bp2.band * env * vel * 0.9;
            if (k >= 0) { dL[k] += l; dR[k] += r; sendL[k] += l * 0.45; sendR[k] += r * 0.45; }
        }
    }

    void hat(double t, double vel, boolean open, double pan) {
        SVF hp = new SVF().set(7800, 0.8);
        int s0 = start(t), len = (int) ((open ? 0.42 : 0.09) * SR);
        double gl = panL(pan), gr = panR(pan);
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            hp.process(noise());
            double v = hp.high * Math.exp(-tt * (open ? 8 : 62)) * vel * 0.26;
            if (k >= 0) { dL[k] += v * gl; dR[k] += v * gr; }
        }
    }

    void crash(double t, double vel) {
        SVF a = new SVF().set(3800, 0.7), b = new SVF().set(4100, 0.7);
        int s0 = start(t), len = (int) (2.8 * SR);
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            double env = Math.exp(-tt * 1.5) * (tt < 0.002 ? tt / 0.002 : 1);
            a.process(noise()); b.process(noise());
            double l = a.high * env * vel * 0.2, r = b.high * env * vel * 0.2;
            if (k >= 0) { dL[k] += l; dR[k] += r; sendL[k] += l * 0.3; sendR[k] += r * 0.3; }
        }
    }

    void impact(double t, double vel) {
        int s0 = start(t), len = (int) (1.8 * SR);
        double ph = 0;
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            ph += TAU * (38 + 40 * Math.exp(-tt * 6)) / SR;
            double v = Math.sin(ph) * Math.exp(-tt * 2.4) * vel * 0.55;
            if (k >= 0) { dL[k] += v; dR[k] += v; }
        }
    }

    void riser(double t0, double dur, double vel) {
        SVF a = new SVF(), b = new SVF();
        int s0 = start(t0), len = (int) (dur * SR);
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double x = (double) i / len;
            if ((i & 31) == 0) {
                double fc = 350 * Math.pow(26, x);
                a.set(fc, 2.2); b.set(fc * 1.04, 2.2);
            }
            a.process(noise()); b.process(noise());
            double amp = x * x * vel * 0.32;
            if (k >= 0) { sL[k] += a.band * amp; sR[k] += b.band * amp; }
        }
    }

    // ------------------------------------------------------------------ tonal
    /** type: 0 house pluck, 1 sustained future, 2 hard distorted, 3 reese */
    void bassNote(double t, double dur, int midi, double vel, int type) {
        double f = mtof(midi);
        double det = type == 3 ? 1.007 : 1.003;
        SVF lp = new SVF();
        int s0 = start(t), len = (int) ((dur + 0.03) * SR);
        double p1 = 0, p2 = 0.37, ps = 0;
        double rel = 0.03;
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            if ((i & 15) == 0) {
                double fc = switch (type) {
                    case 0 -> 170 + 2400 * Math.exp(-tt * 11) * vel;
                    case 1 -> 320 + 1000 * Math.exp(-tt * 3);
                    case 2 -> 380 + 2900 * Math.exp(-tt * 15);
                    default -> 520 + 380 * Math.sin(TAU * 0.6 * (t + tt));
                };
                lp.set(fc, type == 2 ? 1.5 : 0.9);
            }
            double dt1 = f / SR, dt2 = f * det / SR;
            p1 += dt1; if (p1 >= 1) p1 -= 1;
            p2 += dt2; if (p2 >= 1) p2 -= 1;
            ps += TAU * f / SR;
            double s1 = saw(p1, dt1), s2 = saw(p2, dt2);
            double raw = switch (type) {
                case 0 -> s1 * 0.7 + s2 * 0.3;
                case 1 -> (s1 + s2) * 0.45;
                case 2 -> Math.tanh(2.6 * (s1 + s2 * 0.6)) * 0.55;
                default -> Math.tanh(1.6 * (s1 + s2)) * 0.5;
            };
            double v = lp.process(raw) + Math.sin(ps) * (type == 2 ? 0.45 : 0.62);
            double env = Math.min(1, tt / 0.004);
            if (tt > dur) env *= Math.max(0, 1 - (tt - dur) / rel);
            if (k >= 0) bass[k] += (float) (v * env * vel * 0.5);
        }
    }

    private static final double[] SS_DET = {-17, -8, 0, 8, 17};
    private static final double[] SS_PAN = {-0.75, -0.35, 0, 0.35, 0.75};

    void lead(double t, double dur, int midi, double vel, double bright) {
        double base = mtof(midi);
        double[] ph = new double[5];
        for (int v = 0; v < 5; v++) ph[v] = Math.abs(noise());
        double[] gl = new double[5], gr = new double[5], fr = new double[5];
        for (int v = 0; v < 5; v++) {
            gl[v] = panL(SS_PAN[v]); gr[v] = panR(SS_PAN[v]);
            fr[v] = Math.pow(2, SS_DET[v] / 1200.0);
        }
        SVF fl = new SVF(), fRt = new SVF();
        double a = 0.006, dcy = 0.2, sus = 0.62, r = 0.16;
        int s0 = start(t), len = (int) ((dur + r) * SR);
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            if ((i & 31) == 0) {
                double fc = 900 + bright * (5400 * Math.exp(-tt * 4.5) + 1300);
                fl.set(fc, 0.8); fRt.set(fc * 1.02, 0.8);
            }
            double vib = tt > 0.22 ? 1 + 0.0045 * Math.sin(TAU * 5.6 * tt) * Math.min(1, (tt - 0.22) * 3) : 1;
            double l = 0, rr = 0;
            for (int v = 0; v < 5; v++) {
                double dt = base * fr[v] * vib / SR;
                ph[v] += dt; if (ph[v] >= 1) ph[v] -= 1;
                double s = saw(ph[v], dt);
                l += s * gl[v]; rr += s * gr[v];
            }
            double env;
            if (tt < a) env = tt / a;
            else if (tt < a + dcy) env = 1 - (1 - sus) * (tt - a) / dcy;
            else env = sus;
            if (tt > dur) env *= Math.max(0, 1 - (tt - dur) / r);
            double g = env * vel * 0.16;
            if (k >= 0) { sL[k] += fl.process(l) * g; sR[k] += fRt.process(rr) * g; }
        }
    }

    void pluck(double t, int midi, double vel, double pan) {
        double f = mtof(midi);
        SVF lp = new SVF();
        int s0 = start(t), len = (int) (0.5 * SR);
        double ph = 0, gl = panL(pan), gr = panR(pan);
        double dt = f / SR;
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            if ((i & 15) == 0) lp.set(450 + 5200 * Math.exp(-tt * 17), 1.1);
            ph += dt; if (ph >= 1) ph -= 1;
            double ph2 = ph + 0.5; if (ph2 >= 1) ph2 -= 1;
            double pulse = saw(ph, dt) - saw(ph2, dt);
            double v = lp.process(pulse) * Math.exp(-tt * 7.5) * Math.min(1, tt / 0.002) * vel * 0.2;
            if (k >= 0) { sL[k] += v * gl; sR[k] += v * gr; sendL[k] += v * gl * 0.4; sendR[k] += v * gr * 0.4; }
        }
    }

    void pad(double t, double dur, int[] midis, double vel) {
        double att = 0.35, rel = 0.9;
        int s0 = start(t), len = (int) ((dur + rel) * SR);
        int nv = midis.length * 3;
        double[] ph = new double[nv], dts = new double[nv], gl = new double[nv], gr = new double[nv];
        for (int m = 0; m < midis.length; m++) for (int j = 0; j < 3; j++) {
            int v = m * 3 + j;
            dts[v] = mtof(midis[m]) * Math.pow(2, (j - 1) * 11 / 1200.0) / SR;
            ph[v] = Math.abs(noise());
            double pan = (j - 1) * 0.6;
            gl[v] = panL(pan); gr[v] = panR(pan);
        }
        SVF fl = new SVF(), fRt = new SVF();
        for (int i = 0; i < len; i++) {
            int k = s0 + i;
            if (k >= n) break;
            double tt = (double) i / SR;
            if ((i & 63) == 0) {
                double fc = 1000 + 450 * Math.sin(TAU * 0.25 * (t + tt));
                fl.set(fc, 0.7); fRt.set(fc * 1.05, 0.7);
            }
            double l = 0, r = 0;
            for (int v = 0; v < nv; v++) {
                ph[v] += dts[v]; if (ph[v] >= 1) ph[v] -= 1;
                double s = saw(ph[v], dts[v]);
                l += s * gl[v]; r += s * gr[v];
            }
            double env = Math.min(1, tt / att);
            if (tt > dur) env *= Math.max(0, 1 - (tt - dur) / rel);
            double g = env * vel * 0.05;
            if (k >= 0) { pL[k] += fl.process(l) * g; pR[k] += fRt.process(r) * g; }
        }
    }

    // ------------------------------------------------------------------ mixdown
    short[] mixdown(double delaySec, double fadeOutSec) {
        // ping-pong delay on synth bus
        int D = Math.max(1, (int) (delaySec * SR));
        float[] tapL = new float[n], tapR = new float[n];
        double fb = 0.38, wet = 0.26;
        for (int i = D; i < n; i++) {
            double in = (sL[i - D] + sR[i - D]) * 0.5;
            tapL[i] = (float) (in + fb * tapR[i - D]);
            tapR[i] = tapL[i - D];
        }
        for (int i = 0; i < n; i++) { sL[i] += tapL[i] * wet; sR[i] += tapR[i] * wet; }
        tapL = null; tapR = null;

        // reverb (Freeverb-style)
        float[] revL = new float[n], revR = new float[n];
        reverb(revL, revR);

        // sidechain envelope from kicks
        float[] sc = new float[n];
        java.util.Arrays.fill(sc, 1f);
        int scLen = (int) (0.32 * SR);
        for (double kt : kicks) {
            int s0 = start(kt);
            for (int i = 0; i < scLen; i++) {
                int k = s0 + i;
                if (k < 0 || k >= n) continue;
                double tt = (double) i / SR;
                double g = 1 - 0.62 * Math.exp(-tt / 0.085) * Math.min(1, tt / 0.003 + 0.6);
                if (g < sc[k]) sc[k] = (float) g;
            }
        }

        float[] L = new float[n], R = new float[n];
        double peak = 1e-9;
        for (int i = 0; i < n; i++) {
            double s = sc[i];
            double l = dL[i] * 0.95 + (bass[i] * 0.9 + sL[i] + pL[i]) * s + revL[i];
            double r = dR[i] * 0.95 + (bass[i] * 0.9 + sR[i] + pR[i]) * s + revR[i];
            L[i] = (float) l; R[i] = (float) r;
            peak = Math.max(peak, Math.max(Math.abs(l), Math.abs(r)));
        }
        double norm = 1.0 / (peak * 0.72);
        double tn = Math.tanh(1.0);
        short[] out = new short[n * 2];
        int fadeStart = n - (int) (fadeOutSec * SR);
        for (int i = 0; i < n; i++) {
            double fade = 1;
            if (i > fadeStart) fade = Math.max(0, 1 - (double) (i - fadeStart) / (n - fadeStart));
            if (i < 64) fade *= i / 64.0;
            double l = Math.tanh(L[i] * norm) / tn * 0.93 * fade;
            double r = Math.tanh(R[i] * norm) / tn * 0.93 * fade;
            out[2 * i] = (short) Math.max(-32767, Math.min(32767, l * 32767));
            out[2 * i + 1] = (short) Math.max(-32767, Math.min(32767, r * 32767));
        }
        return out;
    }

    private void reverb(float[] outL, float[] outR) {
        int[] combL = {1116, 1188, 1277, 1356, 1422, 1491};
        int spread = 23;
        float[][] bufL = new float[combL.length][], bufR = new float[combL.length][];
        int[] idxL = new int[combL.length], idxR = new int[combL.length];
        double[] fsL = new double[combL.length], fsR = new double[combL.length];
        for (int c = 0; c < combL.length; c++) {
            bufL[c] = new float[combL[c]];
            bufR[c] = new float[combL[c] + spread];
        }
        int[] ap = {556, 441, 341};
        float[][] apL = new float[ap.length][], apR = new float[ap.length][];
        int[] aiL = new int[ap.length], aiR = new int[ap.length];
        for (int a = 0; a < ap.length; a++) { apL[a] = new float[ap[a]]; apR[a] = new float[ap[a] + spread]; }
        double fb = 0.86, damp = 0.25, inGain = 0.045, wet = 0.9;
        for (int i = 0; i < n; i++) {
            double in = (sendL[i] + sendR[i] + (sL[i] + sR[i]) * 0.35 + (pL[i] + pR[i]) * 0.45) * inGain;
            double l = 0, r = 0;
            for (int c = 0; c < combL.length; c++) {
                float[] b = bufL[c];
                double o = b[idxL[c]];
                fsL[c] = o * (1 - damp) + fsL[c] * damp;
                b[idxL[c]] = (float) (in + fsL[c] * fb);
                if (++idxL[c] >= b.length) idxL[c] = 0;
                l += o;
                b = bufR[c];
                o = b[idxR[c]];
                fsR[c] = o * (1 - damp) + fsR[c] * damp;
                b[idxR[c]] = (float) (in + fsR[c] * fb);
                if (++idxR[c] >= b.length) idxR[c] = 0;
                r += o;
            }
            for (int a = 0; a < ap.length; a++) {
                float[] b = apL[a];
                double bo = b[aiL[a]];
                b[aiL[a]] = (float) (l + bo * 0.5);
                l = bo - l;
                if (++aiL[a] >= b.length) aiL[a] = 0;
                b = apR[a];
                bo = b[aiR[a]];
                b[aiR[a]] = (float) (r + bo * 0.5);
                r = bo - r;
                if (++aiR[a] >= b.length) aiR[a] = 0;
            }
            outL[i] = (float) (l * wet);
            outR[i] = (float) (r * wet);
        }
    }
}
