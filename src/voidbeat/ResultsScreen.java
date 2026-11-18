package voidbeat;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.*;

final class ResultsScreen extends Game.Screen {
    private final SongEntry song;
    private final Difficulty diff;
    private final int[] counts;
    private final int score, maxCombo, total;
    private final double acc, mean, ur;
    private final String grade;
    private final boolean fc, newBest, auto;
    private final int[] hist = new int[61];
    private int histMax = 1;
    private double t;
    private final PulseTracker pulses = new PulseTracker();

    ResultsScreen(Game g, PlayScreen p) {
        super(g);
        song = p.song;
        diff = p.diff;
        counts = p.counts.clone();
        score = p.score();
        maxCombo = p.maxCombo;
        total = p.total;
        acc = p.finalAccuracy();
        auto = p.autoplayed();
        fc = counts[5] == 0;
        grade = acc >= 100 - 1e-9 ? "SS" : acc >= 95 ? "S" : acc >= 90 ? "A" : acc >= 80 ? "B" : acc >= 70 ? "C" : "D";
        double m = 0, sq = 0;
        for (double e : p.errors) m += e;
        m = p.errors.isEmpty() ? 0 : m / p.errors.size();
        for (double e : p.errors) sq += (e - m) * (e - m);
        mean = m;
        ur = p.errors.size() > 1 ? Math.sqrt(sq / (p.errors.size() - 1)) * 10 : 0;
        double bw = diff.badWindow();
        for (double e : p.errors) {
            int b = (int) Math.round((e / bw) * 30) + 30;
            b = Math.max(0, Math.min(60, b));
            hist[b]++;
        }
        for (int h : hist) histMax = Math.max(histMax, h);
        newBest = !auto && g.settings.submit(song.id, diff, new Settings.Best(score, Math.round(acc * 100) / 100.0, maxCombo, grade, fc));
    }

    @Override void enter() {
        game.audio.playPreview(song.audio, song.previewSec, 30);
    }

    @Override void update(double dt) {
        t += dt;
        pulses.update(song, game.audio.nowMs() / 1000.0, game.bg);
    }

    @Override void render(Graphics2D g) {
        game.bg.render(g, 0.3);
        double p = game.bg.pulse();

        g.setFont(Theme.font(Font.BOLD, 30));
        g.setColor(Color.WHITE);
        g.drawString(song.title, 60, 70);
        g.setFont(Theme.font(Font.PLAIN, 17));
        g.setColor(Theme.TEXT_DIM);
        g.drawString(song.artist, 60, 96);
        g.setFont(Theme.font(Font.BOLD, 16));
        g.setColor(diff.color);
        g.drawString(diff.label + String.format("   ★ %.2f", song.chart(diff).stars), 60, 122);

        // grade
        double gs = 1 + 0.5 * Math.max(0, 1 - t * 3) + p * 0.04;
        AffineTransform at = g.getTransform();
        g.translate(250, 360);
        g.scale(gs, gs);
        g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 210));
        Color gc = SongSelectScreen.gradeColor(grade);
        Theme.glowText(g, grade, 0, 70, gc, Theme.lerp(gc, Theme.PURPLE, 0.5), 10);
        g.setTransform(at);
        double a = Math.min(1, t * 2);
        if (newBest) {
            g.setFont(Theme.font(Font.BOLD, 22));
            g.setColor(Theme.alpha(Theme.GOLD, a * (0.7 + 0.3 * Math.sin(t * 6))));
            Theme.drawCentered(g, "★ NEW PERSONAL BEST ★", 250, 520);
        }
        if (fc && total > 0) {
            g.setFont(Theme.font(Font.BOLD, 18));
            g.setColor(Theme.alpha(Theme.CYAN, a));
            Theme.drawCentered(g, counts[0] == total ? "ALL MARVELOUS" : "FULL COMBO", 250, 550);
        }
        if (auto) {
            g.setFont(Theme.font(Font.BOLD, 16));
            g.setColor(Theme.GOLD);
            Theme.drawCentered(g, "AUTOPLAY • not saved", 250, 580);
        }

        // panel
        double x = 520, y = 150, w = 700, h = 500;
        g.setColor(Theme.PANEL);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 26, 26));
        g.setColor(Theme.PANEL_EDGE);
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 26, 26));

        int shownScore = (int) (score * Math.min(1, t * 1.6));
        g.setFont(Theme.font(Font.BOLD, 56));
        g.setColor(Color.WHITE);
        g.drawString(String.format("%,d", shownScore), (float) (x + 36), (float) (y + 76));
        g.setFont(Theme.font(Font.BOLD, 24));
        g.setColor(Theme.VIOLET);
        g.drawString(String.format("%.2f%%", acc), (float) (x + 36), (float) (y + 114));
        g.setColor(Theme.BLUE);
        Theme.drawRight(g, maxCombo + "x / " + total, x + w - 36, y + 114);
        g.setFont(Theme.font(Font.BOLD, 12));
        g.setColor(Theme.TEXT_DIM);
        g.drawString("ACCURACY", (float) (x + 36), (float) (y + 132));
        Theme.drawRight(g, "MAX COMBO", x + w - 36, y + 132);

        // judgement bars
        int maxC = 1;
        for (int c : counts) maxC = Math.max(maxC, c);
        double by = y + 170;
        for (int i = 0; i < 6; i++) {
            Judgement j = Judgement.values()[i];
            double yy = by + i * 30;
            g.setFont(Theme.font(Font.BOLD, 14));
            g.setColor(j.color);
            g.drawString(j.label, (float) (x + 36), (float) (yy + 14));
            double bw = (w - 300) * counts[i] / (double) maxC * Math.min(1, t * 2);
            g.setColor(Theme.alpha(j.color, 0.25));
            g.fill(new RoundRectangle2D.Double(x + 160, yy, w - 300, 18, 9, 9));
            g.setColor(Theme.alpha(j.color, 0.85));
            g.fill(new RoundRectangle2D.Double(x + 160, yy, Math.max(4, bw), 18, 9, 9));
            g.setFont(Theme.font(Font.BOLD, 16));
            g.setColor(Color.WHITE);
            Theme.drawRight(g, String.valueOf(counts[i]), x + w - 36, yy + 15);
        }

        // hit error histogram
        double hx = x + 36, hy = y + 440, hw = w - 72, hh = 70;
        g.setFont(Theme.font(Font.BOLD, 12));
        g.setColor(Theme.TEXT_DIM);
        g.drawString("TIMING", (float) hx, (float) (hy - hh - 8));
        g.setColor(Theme.alpha(Color.WHITE, 0.3));
        g.fill(new Rectangle2D.Double(hx + hw / 2 - 0.5, hy - hh, 1, hh));
        double cw = hw / hist.length;
        for (int i = 0; i < hist.length; i++) {
            double v = hist[i] / (double) histMax;
            double bh = v * hh * Math.min(1, t * 2);
            double e = (i - 30) / 30.0 * diff.badWindow();
            Judgement j = diff.judge(Math.abs(e), 1);
            g.setColor(Theme.alpha(j == null ? Judgement.MISS.color : j.color, 0.85));
            g.fill(new Rectangle2D.Double(hx + i * cw + 1, hy - bh, cw - 2, bh));
        }
        g.setFont(Theme.font(Font.PLAIN, 13));
        g.setColor(Theme.TEXT_DIM);
        g.drawString("early", (float) hx, (float) (hy + 18));
        Theme.drawRight(g, "late", hx + hw, hy + 18);
        String tip = String.format("mean %+.1f ms   •   UR %.0f", mean, ur);
        if (Math.abs(mean) > 10 && !auto)
            tip += mean > 0 ? "   •   hitting late: try offset +" + Math.round(mean / 5) * 5
                            : "   •   hitting early: try offset " + Math.round(mean / 5) * 5;
        Theme.drawCentered(g, tip, hx + hw / 2, hy + 18);

        g.setFont(Theme.font(Font.PLAIN, 14));
        g.setColor(Theme.TEXT_DIM);
        Theme.drawCentered(g, "ENTER continue   •   R retry", Game.W / 2.0, 700);
    }

    @Override void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, KeyEvent.VK_SPACE -> { game.ui(); game.setScreen(new SongSelectScreen(game)); }
            case KeyEvent.VK_R, KeyEvent.VK_BACK_QUOTE -> { game.ui(); game.setScreen(new PlayScreen(game, song, diff)); }
            default -> { }
        }
    }
}
