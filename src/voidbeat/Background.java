package voidbeat;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.Random;

/** Animated backdrop: gradient void, drifting motes, synthwave grid, beat pulse, live spectrum. */
final class Background {
    static final int W = 1280, H = 720;
    private final BufferedImage base, glowImg, horizonImg;
    private final float[] motesX = new float[90], motesY = new float[90], motesS = new float[90], motesV = new float[90];
    private double time;
    private double pulse;           // 0..1, decays
    private final AudioEngine audio;
    private final float[] win = new float[2048];
    private final double[] re = new double[2048], im = new double[2048];
    static final int BANDS = 56;
    final double[] spectrum = new double[BANDS];
    private double level;           // overall loudness 0..1 (smoothed)
    private final double[] hann = new double[2048];

    Background(AudioEngine audio) {
        this.audio = audio;
        base = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = base.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setPaint(new GradientPaint(0, 0, new Color(0x05020a), 0, H, new Color(0x0d0518)));
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(W / 2f, H * 0.62f), W * 0.6f,
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(70, 22, 120, 150), new Color(30, 10, 60, 80), new Color(0, 0, 0, 0)}));
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(W * 0.12f, H * 0.1f), W * 0.35f,
                new float[]{0f, 1f}, new Color[]{new Color(30, 60, 140, 60), new Color(0, 0, 0, 0)}));
        g.fillRect(0, 0, W, H);
        g.dispose();
        glowImg = new BufferedImage(760, 420, BufferedImage.TYPE_INT_ARGB);
        g = glowImg.createGraphics();
        g.setPaint(new RadialGradientPaint(new Point2D.Float(380, 210), 380, new float[]{0f, 1f},
                new Color[]{Theme.alpha(Theme.PURPLE, 0.28), new Color(0, 0, 0, 0)}));
        g.fillRect(0, 0, 760, 420);
        g.dispose();
        horizonImg = new BufferedImage(W, 28, BufferedImage.TYPE_INT_ARGB);
        g = horizonImg.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Theme.VIOLET, 0, 28, new Color(Theme.VIOLET.getRed(), Theme.VIOLET.getGreen(), Theme.VIOLET.getBlue(), 0)));
        g.fillRect(0, 0, W, 28);
        g.dispose();
        Random r = new Random(5);
        for (int i = 0; i < motesX.length; i++) {
            motesX[i] = r.nextFloat() * W;
            motesY[i] = r.nextFloat() * H;
            motesS[i] = 1 + r.nextFloat() * 2.2f;
            motesV[i] = 6 + r.nextFloat() * 22;
        }
        for (int i = 0; i < 2048; i++) hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / 2047);
    }

    void kick(double strength) { pulse = Math.max(pulse, strength); }

    double pulse() { return pulse; }

    double level() { return level; }

    void update(double dt) {
        time += dt;
        pulse *= Math.exp(-dt * 7.5);
        for (int i = 0; i < motesX.length; i++) {
            motesY[i] -= motesV[i] * dt * (1 + pulse * 2);
            if (motesY[i] < -5) { motesY[i] = H + 5; }
        }
        // spectrum
        audio.window(win);
        for (int i = 0; i < 2048; i++) { re[i] = win[i] * hann[i]; im[i] = 0; }
        CustomSongs.fft(re, im);
        double binHz = 44100.0 / 2048;
        double sum = 0;
        for (int b = 0; b < BANDS; b++) {
            double f0 = 35 * Math.pow(14000 / 35.0, (double) b / BANDS);
            double f1 = 35 * Math.pow(14000 / 35.0, (double) (b + 1) / BANDS);
            int k0 = Math.max(1, (int) (f0 / binHz)), k1 = Math.max(k0 + 1, (int) (f1 / binHz));
            double m = 0;
            for (int k = k0; k < k1 && k < 1024; k++) m = Math.max(m, Math.hypot(re[k], im[k]));
            double db = 20 * Math.log10(m + 1e-6);
            double v = Math.max(0, Math.min(1, (db + 12) / 50.0 + b * 0.004));
            double a = v > spectrum[b] ? 0.55 : 0.12;
            spectrum[b] += (v - spectrum[b]) * Math.min(1, a * dt * 60);
            sum += spectrum[b];
        }
        level += (sum / BANDS - level) * Math.min(1, dt * 8);
    }

    private final java.util.Map<Integer, BufferedImage> dimmed = new java.util.HashMap<>();

    private BufferedImage baseFor(double dim) {
        int key = (int) Math.round(dim * 100);
        return dimmed.computeIfAbsent(key, k -> {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.drawImage(base, 0, 0, null);
            g.setColor(new Color(0, 0, 0, (int) (k / 100.0 * 255)));
            g.fillRect(0, 0, W, H);
            g.dispose();
            return img;
        });
    }

    void render(Graphics2D g, double dim) {
        g.drawImage(baseFor(dim), 0, 0, null);
        double keep = 1 - dim;
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        // pulse glow
        if (pulse > 0.03) {
            Composite oc = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, pulse * keep)));
            g.drawImage(glowImg, W / 2 - 380, (int) (H * 0.6) - 210, null);
            g.setComposite(oc);
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        drawGrid(g, keep);
        // motes
        for (int i = 0; i < motesX.length; i++) {
            double tw = 0.35 + 0.35 * Math.sin(time * 1.7 + i) + pulse * 0.3;
            g.setColor(Theme.alpha(i % 3 == 0 ? Theme.BLUE : Theme.VIOLET, Math.max(0, Math.min(1, tw)) * 0.6 * keep));
            int s = Math.round(motesS[i]);
            g.fillRect((int) motesX[i], (int) motesY[i], s, s);
        }
        drawSpectrum(g, keep);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    private void drawGrid(Graphics2D g, double keep) {
        int horizon = (int) (H * 0.58f);
        double scroll = (time * 0.6) % 1.0;
        for (int i = 0; i < 14; i++) {
            double z = (i + 1 - scroll);
            double y = horizon + (H - horizon) * (1.0 / (z * 0.35 + 0.0001)) * 0.12;
            if (y > H || y < horizon) continue;
            double a = Math.min(1, (y - horizon) / (H - horizon) * 1.6) * (0.22 + pulse * 0.3) * keep;
            g.setColor(Theme.alpha(Theme.PURPLE, a));
            g.fillRect(0, (int) y, W, 1);
        }
        for (int i = -16; i <= 16; i++) {
            int xb = W / 2 + i * 110;
            int xt = W / 2 + i * 8;
            g.setColor(Theme.alpha(i % 4 == 0 ? Theme.BLUE : Theme.PURPLE, (0.10 + pulse * 0.12) * keep));
            g.drawLine(xt, horizon, xb, H);
        }
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, (0.35 + pulse * 0.3) * keep)));
        g.drawImage(horizonImg, 0, horizon - 2, null);
        g.setComposite(oc);
    }

    private void drawSpectrum(Graphics2D g, double keep) {
        double bw = (double) W / BANDS;
        for (int b = 0; b < BANDS; b++) {
            double v = spectrum[b];
            if (v < 0.02) continue;
            double h = v * 150;
            Color c = Theme.lerp(Theme.PURPLE, Theme.CYAN, (double) b / BANDS);
            g.setColor(Theme.alpha(c, (0.18 + v * 0.25) * keep));
            g.fillRect((int) (b * bw + 2), (int) (H - h), (int) bw - 4, (int) h + 1);
            g.setColor(Theme.alpha(c, 0.6 * keep));
            g.fillRect((int) (b * bw + 2), (int) (H - h - 3), (int) bw - 4, 2);
        }
    }
}
