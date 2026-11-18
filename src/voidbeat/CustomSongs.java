package voidbeat;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Loads user audio from the ./songs folder (WAV / AIFF / AU) and auto-charts it:
 * spectral-flux onset detection in three bands, tempo + beat-phase estimation, loudness-based
 * energy sections. The resulting events go through the same {@link ChartGenerator} as built-ins.
 * File name "Artist - Title.wav" sets the metadata.
 */
final class CustomSongs {
    private CustomSongs() {}

    static final Path FOLDER = Paths.get("songs");

    static List<SongEntry> scan() {
        List<SongEntry> out = new ArrayList<>();
        try {
            Files.createDirectories(FOLDER);
        } catch (IOException ignored) { }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(FOLDER)) {
            List<Path> files = new ArrayList<>();
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".wav") || n.endsWith(".aif") || n.endsWith(".aiff") || n.endsWith(".au")) files.add(p);
            }
            Collections.sort(files);
            for (Path p : files) {
                String base = p.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                String artist = "Unknown Artist", title = base;
                int dash = base.indexOf(" - ");
                if (dash > 0) { artist = base.substring(0, dash).trim(); title = base.substring(dash + 3).trim(); }
                SongEntry e = new SongEntry("custom:" + p.getFileName(), title, artist, "CUSTOM");
                e.sourcePath = p;
                out.add(e);
            }
        } catch (IOException ignored) { }
        return out;
    }

    static void load(SongEntry e) {
        try {
            float[][] st = decode(e.sourcePath.toFile());
            float[] l = st[0], r = st[1];
            int n = l.length;
            short[] pcm = new short[n * 2];
            float[] mono = new float[n];
            for (int i = 0; i < n; i++) {
                pcm[2 * i] = (short) Math.max(-32767, Math.min(32767, l[i] * 32767));
                pcm[2 * i + 1] = (short) Math.max(-32767, Math.min(32767, r[i] * 32767));
                mono[i] = (l[i] + r[i]) * 0.5f;
            }
            e.lengthSec = n / (double) Synth.SR;
            analyze(e, mono);
            e.audio = pcm;
            e.ready = true;
        } catch (Throwable t) {
            e.error = "Can't load: " + t.getMessage();
            System.err.println("[voidbeat] " + e.sourcePath + ": " + t);
        }
    }

    /** Decodes to stereo float at 44.1 kHz. */
    static float[][] decode(File f) throws Exception {
        try (AudioInputStream src = AudioSystem.getAudioInputStream(f)) {
            AudioFormat bf = src.getFormat();
            int ch = bf.getChannels();
            float rate = bf.getSampleRate();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, ch, ch * 2, rate, false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, src)) {
                byte[] bytes = pcm.readAllBytes();
                int frames = bytes.length / (2 * ch);
                float[] l = new float[frames], r = new float[frames];
                for (int i = 0; i < frames; i++) {
                    int o = i * 2 * ch;
                    float a = (short) ((bytes[o] & 0xff) | (bytes[o + 1] << 8)) / 32768f;
                    float b = a;
                    if (ch > 1) b = (short) ((bytes[o + 2] & 0xff) | (bytes[o + 3] << 8)) / 32768f;
                    l[i] = a; r[i] = b;
                }
                if (Math.abs(rate - Synth.SR) > 1) { l = resample(l, rate); r = resample(r, rate); }
                return new float[][]{l, r};
            }
        }
    }

    private static float[] resample(float[] in, float rate) {
        double ratio = rate / Synth.SR;
        int n = (int) (in.length / ratio);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double x = i * ratio;
            int k = (int) x;
            double f = x - k;
            float a = in[Math.min(k, in.length - 1)], b = in[Math.min(k + 1, in.length - 1)];
            out[i] = (float) (a + (b - a) * f);
        }
        return out;
    }

    // ------------------------------------------------------------------ analysis
    private static final int N = 1024, HOP = 441; // 10 ms frames
    private static final double FPS = (double) Synth.SR / HOP;

    static void analyze(SongEntry e, float[] x) {
        int frames = Math.max(1, (x.length - N) / HOP);
        float[] lo = new float[frames], mid = new float[frames], hi = new float[frames];
        float[] cent = new float[frames], rms = new float[frames];
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
        double[] re = new double[N], im = new double[N], mag = new double[N / 2], prev = new double[N / 2];
        double binHz = (double) Synth.SR / N;
        int b1 = (int) (160 / binHz), b2 = (int) (2500 / binHz);
        for (int f = 0; f < frames; f++) {
            int o = f * HOP;
            double sq = 0;
            for (int i = 0; i < N; i++) { double v = x[o + i]; re[i] = v * win[i]; im[i] = 0; sq += v * v; }
            rms[f] = (float) Math.sqrt(sq / N);
            fft(re, im);
            double fl = 0, fm = 0, fh = 0, cw = 0, cs = 0;
            for (int k = 1; k < N / 2; k++) {
                double m = Math.log1p(100 * Math.hypot(re[k], im[k]));
                mag[k] = m;
                double d = m - prev[k];
                if (d > 0) { if (k <= b1) fl += d; else if (k <= b2) fm += d; else fh += d; }
                cw += k * m; cs += m;
            }
            System.arraycopy(mag, 0, prev, 0, mag.length);
            lo[f] = (float) fl; mid[f] = (float) fm; hi[f] = (float) fh;
            cent[f] = (float) (cs > 0 ? cw / cs * binHz : 0);
        }
        normalize(lo); normalize(mid); normalize(hi);
        float[] comb = new float[frames];
        for (int f = 0; f < frames; f++) comb[f] = lo[f] * 1.0f + mid[f] * 0.8f + hi[f] * 0.45f;
        normalize(comb);

        // ---- tempo
        double bpm = estimateBpm(comb);
        double period = FPS * 60 / bpm;
        double bestPhase = 0, bestSc = -1;
        for (double ph = 0; ph < period; ph += 0.25) {
            double sc = 0;
            for (double t = ph; t < frames - 1; t += period) sc += interp(comb, t) + 0.5 * interp(lo, t);
            if (sc > bestSc) { bestSc = sc; bestPhase = ph; }
        }
        e.bpm = Math.round(bpm * 100) / 100.0;
        double frameOff = N / 2.0 / Synth.SR;
        e.beatOffset = bestPhase / FPS + frameOff;
        // move offset so it lands on a bar start nearest the loudest early downbeat (keep it simple: first beat)
        while (e.beatOffset - 60 / bpm * 4 > 0) e.beatOffset -= 60 / bpm * 4;

        // ---- energy (4 s smoothed loudness → percentile)
        int wlen = (int) (FPS * 4);
        float[] en = new float[frames];
        double acc = 0;
        for (int f = 0; f < frames; f++) {
            acc += rms[f];
            if (f >= wlen) acc -= rms[f - wlen];
            en[Math.max(0, f - wlen / 2)] = (float) (acc / Math.min(f + 1, wlen));
        }
        for (int f = Math.max(0, frames - wlen / 2); f < frames; f++) en[f] = en[Math.max(0, frames - wlen / 2 - 1)];
        float[] sorted = en.clone();
        Arrays.sort(sorted);
        float p35 = sorted[(int) (frames * 0.35)], p70 = sorted[(int) (frames * 0.7)];

        // ---- onsets
        List<int[]> ons = new ArrayList<>();
        for (int f = 3; f < frames - 3; f++) {
            float v = comb[f];
            boolean peak = true;
            for (int j = -3; j <= 3 && peak; j++) if (j != 0 && comb[f + j] > v) peak = false;
            if (!peak) continue;
            double mean = 0;
            int c = 0;
            for (int j = Math.max(0, f - 12); j < Math.min(frames, f + 12); j++) { mean += comb[j]; c++; }
            mean /= c;
            if (v > mean * 1.3 + 0.06) ons.add(new int[]{f});
        }
        double spb = 60 / bpm;
        double prevPreview = -1;
        for (int i = 0; i < ons.size(); i++) {
            int f = ons.get(i)[0];
            double t = f / FPS + frameOff - 0.005;
            int energy = en[f] >= p70 ? 2 : en[f] >= p35 ? 1 : 0;
            if (energy == 2 && prevPreview < 0 && t > 10) prevPreview = t;
            float l = lo[f], m = mid[f], h = hi[f];
            double strength = Math.min(1.2, comb[f]);
            ChartEvent.Kind kind;
            int pitch = -1;
            double w;
            if (l >= m && l >= h * 1.2) { kind = ChartEvent.Kind.KICK; w = 0.45 + 0.45 * strength; }
            else if (m >= h) {
                kind = ChartEvent.Kind.LEAD; w = 0.35 + 0.6 * strength;
                pitch = (int) Math.round(48 + 12 * Math.log(Math.max(100, cent[f]) / 300) / Math.log(2) * 1.5);
            } else { kind = ChartEvent.Kind.HAT; w = 0.15 + 0.5 * strength; }
            if (kind == ChartEvent.Kind.KICK && strength > 0.9 && m > 0.5) {
                e.events.add(new ChartEvent(ChartEvent.Kind.SNARE, t, 0, -1, 0.7, energy));
            }
            // sustained part between onsets → hold candidate
            double next = e.lengthSec;
            for (int k = i + 1; k < ons.size(); k++) {
                if (comb[ons.get(k)[0]] > 0.55) { next = ons.get(k)[0] / FPS + frameOff; break; }
            }
            double gap = next - t;
            double dur = 0;
            if (gap > spb * 1.4 && strength > 0.5 && kind != ChartEvent.Kind.HAT) {
                int f2 = Math.min(frames - 1, (int) ((t + gap * 0.7) * FPS));
                if (rms[f2] > rms[f] * 0.5) {
                    e.events.add(new ChartEvent(ChartEvent.Kind.PAD, t, gap, 60, w, energy));
                }
            }
            if (kind == ChartEvent.Kind.KICK) e.pulses.add(t);
            e.events.add(new ChartEvent(kind, t, dur, pitch, w, energy));
        }
        if (e.pulses.size() < 16) {
            e.pulses.clear();
            for (double t = e.beatOffset; t < e.lengthSec; t += spb) e.pulses.add(t);
        }
        e.previewSec = prevPreview > 0 ? prevPreview : e.lengthSec * 0.35;
    }

    private static double estimateBpm(float[] env) {
        int n = env.length;
        double best = 120, bestSc = -1;
        double mean = 0;
        for (float v : env) mean += v;
        mean /= Math.max(1, n);
        for (double bpm = 70; bpm <= 190; bpm += 0.5) {
            double lag = FPS * 60 / bpm;
            double sc = 0;
            for (int m = 1; m <= 4; m++) {
                double L = lag * m;
                int cnt = 0;
                double s = 0;
                for (int i = 0; i + L + 1 < n; i += 2) { s += (env[i] - mean) * (interp(env, i + L) - mean); cnt++; }
                sc += cnt > 0 ? s / cnt / m : 0;
            }
            double prior = Math.exp(-Math.pow(Math.log(bpm / 135) / 0.45, 2));
            sc *= 0.6 + 0.4 * prior;
            if (sc > bestSc) { bestSc = sc; best = bpm; }
        }
        // fine search
        double lo = best - 0.6, hi = best + 0.6, fb = best;
        bestSc = -1;
        for (double bpm = lo; bpm <= hi; bpm += 0.02) {
            double period = FPS * 60 / bpm;
            double top = 0;
            for (double ph = 0; ph < period; ph += 0.5) {
                double sc = 0;
                for (double t = ph; t < n - 1; t += period) sc += interp(env, t);
                top = Math.max(top, sc);
            }
            if (top > bestSc) { bestSc = top; fb = bpm; }
        }
        return fb;
    }

    private static double interp(float[] a, double x) {
        int i = (int) x;
        if (i < 0 || i + 1 >= a.length) return 0;
        double f = x - i;
        return a[i] + (a[i + 1] - a[i]) * f;
    }

    private static void normalize(float[] a) {
        float[] s = a.clone();
        Arrays.sort(s);
        float p = s[(int) (s.length * 0.97)];
        if (p <= 1e-9f) p = 1;
        for (int i = 0; i < a.length; i++) a[i] /= p;
    }

    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
