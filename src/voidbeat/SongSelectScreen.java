package voidbeat;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.*;
import java.util.List;

final class SongSelectScreen extends Game.Screen {
    private int sel;
    private long selAbs;            // unbounded selection index so the carousel can wrap smoothly
    private int diff;
    private double scroll;          // animated list position
    private double t;
    private double previewDelay;
    private SongEntry previewing;
    private final PulseTracker pulses = new PulseTracker();
    private double diffAnim;

    SongSelectScreen(Game g) {
        super(g);
        sel = g.settings.lastSong;
        diff = g.settings.lastDiff;
    }

    @Override void enter() {
        List<SongEntry> s = game.library.songs();
        sel = Math.max(0, Math.min(sel, s.size() - 1));
        selAbs = sel;
        scroll = sel;
        diffAnim = diff;
        previewDelay = 0.15;
        previewing = null;
    }

    private SongEntry current() {
        List<SongEntry> s = game.library.songs();
        return s.isEmpty() ? null : s.get(Math.max(0, Math.min(sel, s.size() - 1)));
    }

    @Override void update(double dt) {
        t += dt;
        scroll += (selAbs - scroll) * Math.min(1, dt * 14);
        diffAnim += (diff - diffAnim) * Math.min(1, dt * 16);
        SongEntry cur = current();
        if (cur == null) return;
        game.library.request(cur);
        previewDelay -= dt;
        if (previewDelay <= 0 && cur.ready && previewing != cur) {
            previewing = cur;
            game.audio.playPreview(cur.audio, cur.previewSec, 30);
        }
        if (previewing != null) pulses.update(previewing, game.audio.nowMs() / 1000.0, game.bg);
    }

    @Override void render(Graphics2D g) {
        game.bg.render(g, 0.25);
        List<SongEntry> songs = game.library.songs();
        SongEntry cur = current();

        // header
        g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 34));
        Theme.glowText(g, "SELECT SONG", 190, 64, Theme.TEXT, Theme.PURPLE, 4);
        g.setFont(Theme.font(Font.PLAIN, 14));
        g.setColor(Theme.TEXT_DIM);
        g.drawString(songs.size() + " tracks", 70, 88);

        // list
        Shape oldClip = g.getClip();
        g.clipRect(0, 100, 620, 560);
        int n = songs.size();
        int reach = n >= 9 ? 4 : Math.max(0, (n - 1) / 2);
        long center = Math.round(scroll);
        for (long v = center - reach - 1; v <= center + reach + 1; v++) {
            if (n == 0) break;
            double off = v - scroll;
            if (Math.abs(off) > reach + 0.6) continue;
            int i = (int) Math.floorMod(v, (long) n);
            SongEntry s = songs.get(i);
            boolean selected = v == selAbs;
            double y = 370 + off * 92;
            double x = 40 + Math.abs(off) * 18 + (selected ? 0 : 14);
            double w = 540, h = 80;
            float a = (float) Math.max(0.15, 1 - Math.abs(off) * 0.2);
            if (selected) {
                double gp = game.bg.pulse();
                g.setColor(Theme.alpha(Theme.PURPLE, 0.25 + gp * 0.25));
                g.fill(new RoundRectangle2D.Double(x - 6, y - 6, w + 12, h + 12, 26, 26));
            }
            g.setPaint(new GradientPaint((float) x, 0, selected ? new Color(48, 18, 86, 240) : new Color(18, 8, 34, (int) (220 * a)),
                    (float) (x + w), 0, selected ? new Color(18, 40, 80, 230) : new Color(10, 6, 22, (int) (200 * a))));
            g.fill(new RoundRectangle2D.Double(x, y, w, h, 20, 20));
            g.setColor(selected ? Theme.VIOLET : Theme.alpha(Theme.PANEL_EDGE, a));
            g.setStroke(new BasicStroke(selected ? 2f : 1f));
            g.draw(new RoundRectangle2D.Double(x, y, w, h, 20, 20));
            // accent bar
            g.setColor(s.tag.equals("CUSTOM") ? Theme.CYAN : Theme.PURPLE);
            g.fill(new RoundRectangle2D.Double(x + 10, y + 14, 5, h - 28, 4, 4));
            g.setFont(Theme.font(Font.BOLD, 24));
            g.setColor(Theme.alpha(selected ? Color.WHITE : Theme.TEXT, a));
            g.drawString(s.title, (float) (x + 30), (float) (y + 36));
            g.setFont(Theme.font(Font.PLAIN, 15));
            g.setColor(Theme.alpha(Theme.TEXT_DIM, a));
            g.drawString(s.artist + "   •   " + (s.ready || s.def != null ? Math.round(s.bpm) + " BPM" : "? BPM"),
                    (float) (x + 30), (float) (y + 60));
            g.setFont(Theme.font(Font.BOLD, 11));
            String tag = s.error != null ? "ERROR" : !s.ready ? "LOADING" : s.tag;
            int tw = g.getFontMetrics().stringWidth(tag) + 14;
            g.setColor(Theme.alpha(s.error != null ? Judgement.MISS.color : s.tag.equals("CUSTOM") ? Theme.CYAN : Theme.PURPLE, 0.25 * a));
            g.fill(new RoundRectangle2D.Double(x + w - tw - 16, y + 14, tw, 20, 10, 10));
            g.setColor(Theme.alpha(Theme.TEXT, a));
            g.drawString(tag, (float) (x + w - tw - 9), (float) (y + 28));
            Settings.Best b = game.settings.best(s.id, Difficulty.values()[diff]);
            if (b != null) {
                g.setFont(Theme.font(Font.BOLD, 20));
                g.setColor(Theme.alpha(gradeColor(b.grade()), a));
                Theme.drawRight(g, b.grade(), x + w - 18, y + 64);
            }
        }
        g.setClip(oldClip);
        g.setStroke(new BasicStroke(1f));

        if (songs.isEmpty() || cur == null) return;
        renderDetails(g, cur);

        g.setFont(Theme.font(Font.PLAIN, 14));
        g.setColor(Theme.TEXT_DIM);
        Theme.drawCentered(g, "↑↓ song   •   ←→ difficulty   •   ENTER play   •   F3/F4 speed   •   TAB autoplay   •   F5 rescan /songs   •   ESC back",
                Game.W / 2.0, 700);
    }

    private void renderDetails(Graphics2D g, SongEntry s) {
        double x = 650, y = 110, w = 590, h = 560;
        g.setColor(Theme.PANEL);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 26, 26));
        g.setColor(Theme.PANEL_EDGE);
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 26, 26));

        g.setFont(Theme.font(Font.BOLD, 34));
        g.setColor(Color.WHITE);
        g.drawString(s.title, (float) (x + 32), (float) (y + 58));
        g.setFont(Theme.font(Font.PLAIN, 18));
        g.setColor(Theme.VIOLET);
        g.drawString(s.artist, (float) (x + 32), (float) (y + 86));

        int len = (int) Math.round(s.lengthSec);
        String meta = (s.ready || s.def != null ? String.format("%.0f BPM", s.bpm) : "analyzing…") + "    "
                + (len > 0 ? (len / 60) + ":" + String.format("%02d", len % 60) : "--:--");
        g.setFont(Theme.font(Font.BOLD, 16));
        g.setColor(Theme.TEXT_DIM);
        g.drawString(meta, (float) (x + 32), (float) (y + 116));

        // difficulty tabs
        double ty = y + 145, tw = (w - 64) / 4.0;
        double hx = x + 32 + diffAnim * tw;
        Difficulty cd = Difficulty.values()[diff];
        g.setColor(Theme.alpha(cd.color, 0.22));
        g.fill(new RoundRectangle2D.Double(hx, ty, tw - 8, 74, 18, 18));
        g.setColor(cd.color);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(hx, ty, tw - 8, 74, 18, 18));
        g.setStroke(new BasicStroke(1f));
        for (Difficulty d : Difficulty.values()) {
            double dx = x + 32 + d.ordinal() * tw;
            boolean on = d.ordinal() == diff;
            g.setFont(Theme.font(Font.BOLD, 17));
            g.setColor(on ? Color.WHITE : Theme.alpha(d.color, 0.8));
            Theme.drawCentered(g, d.label, dx + (tw - 8) / 2, ty + 30);
            g.setFont(Theme.font(Font.PLAIN, 15));
            g.setColor(on ? Theme.TEXT : Theme.TEXT_DIM);
            String st = s.ready ? String.format("★ %.2f", s.chart(d).stars) : "★ …";
            Theme.drawCentered(g, st, dx + (tw - 8) / 2, ty + 56);
        }

        double iy = ty + 112;
        if (s.error != null) {
            g.setColor(Judgement.MISS.color);
            g.setFont(Theme.font(Font.PLAIN, 16));
            g.drawString(s.error, (float) (x + 32), (float) iy);
            return;
        }
        if (!s.ready) {
            g.setFont(Theme.font(Font.BOLD, 18));
            g.setColor(Theme.TEXT_DIM);
            String what = s.def != null ? "Composing & mixing track" : "Analyzing audio & generating charts";
            g.drawString(what + ".".repeat(1 + (int) (t * 3) % 3), (float) (x + 32), (float) iy);
            return;
        }
        Chart c = s.chart(cd);
        stat(g, "NOTES", String.valueOf(c.notes.size() - c.holdCount()), x + 32, iy);
        stat(g, "HOLDS", String.valueOf(c.holdCount()), x + 172, iy);
        stat(g, "MAX COMBO", String.valueOf(c.judgementCount()), x + 312, iy);
        stat(g, "SPEED", String.valueOf(game.settings.scrollSpeed), x + 470, iy);

        // timing windows
        double wy = iy + 70;
        g.setFont(Theme.font(Font.BOLD, 14));
        g.setColor(Theme.TEXT_DIM);
        g.drawString("TIMING WINDOWS", (float) (x + 32), (float) wy);
        String feel = switch (cd) {
            case EASY -> "very forgiving • early taps never punish";
            case NORMAL -> "relaxed";
            case HARD -> "osu!mania OD8";
            case EXPERT -> "osu!mania OD9";
        };
        g.setFont(Theme.font(Font.PLAIN, 14));
        g.setColor(cd.color);
        Theme.drawRight(g, feel, x + w - 32, wy);
        double bx = x + 32, bw = w - 64, by = wy + 14;
        double maxW = Difficulty.EASY.badWindow();
        for (int j = 4; j >= 0; j--) {
            Judgement jj = Judgement.values()[j];
            double ww = cd.window(jj) / maxW * bw / 2;
            g.setColor(Theme.alpha(jj.color, 0.75));
            g.fill(new Rectangle2D.Double(bx + bw / 2 - ww, by, ww * 2, 14));
        }
        g.setFont(Theme.font(Font.PLAIN, 12));
        g.setColor(Theme.TEXT_DIM);
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < 5; j++) {
            if (j > 0) sb.append("   ");
            sb.append(Judgement.values()[j].label.charAt(0)).append(Judgement.values()[j].label.substring(1).toLowerCase())
              .append(" ±").append((int) cd.windows[j]);
        }
        Theme.drawCentered(g, sb.toString(), bx + bw / 2, by + 34);

        // personal best
        double py = by + 80;
        Settings.Best b = game.settings.best(s.id, cd);
        g.setFont(Theme.font(Font.BOLD, 14));
        g.setColor(Theme.TEXT_DIM);
        g.drawString("PERSONAL BEST", (float) (x + 32), (float) py);
        if (b == null) {
            g.setFont(Theme.font(Font.PLAIN, 18));
            g.setColor(Theme.TEXT_FAINT);
            g.drawString("not played yet", (float) (x + 32), (float) (py + 34));
        } else {
            g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 54));
            g.setColor(gradeColor(b.grade()));
            g.drawString(b.grade(), (float) (x + 32), (float) (py + 62));
            g.setFont(Theme.font(Font.BOLD, 24));
            g.setColor(Color.WHITE);
            g.drawString(String.format("%,d", b.score()), (float) (x + 130), (float) (py + 36));
            g.setFont(Theme.font(Font.PLAIN, 16));
            g.setColor(Theme.TEXT_DIM);
            g.drawString(String.format("%.2f%%   •   %dx%s", b.acc(), b.combo(), b.fc() ? "   •   FULL COMBO" : ""),
                    (float) (x + 130), (float) (py + 62));
        }

        if (game.settings.autoplay) {
            g.setFont(Theme.font(Font.BOLD, 16));
            g.setColor(Theme.GOLD);
            g.drawString("AUTOPLAY ON (scores not saved)", (float) (x + 32), (float) (y + h - 26));
        }
        g.setFont(Theme.font(Font.BOLD, 18));
        double pulse = 0.6 + 0.4 * Math.sin(t * 4);
        g.setColor(Theme.alpha(Theme.CYAN, pulse));
        Theme.drawRight(g, "ENTER ▶", x + w - 32, y + h - 26);
    }

    private void stat(Graphics2D g, String label, String v, double x, double y) {
        g.setFont(Theme.font(Font.BOLD, 12));
        g.setColor(Theme.TEXT_DIM);
        g.drawString(label, (float) x, (float) y);
        g.setFont(Theme.font(Font.BOLD, 26));
        g.setColor(Color.WHITE);
        g.drawString(v, (float) x, (float) (y + 30));
    }

    static Color gradeColor(String grade) {
        return switch (grade) {
            case "SS" -> new Color(0xe0f2fe);
            case "S" -> Theme.CYAN;
            case "A" -> Theme.VIOLET;
            case "B" -> Theme.BLUE;
            case "C" -> Theme.GOLD;
            default -> Judgement.MISS.color;
        };
    }

    @Override void keyPressed(KeyEvent e) {
        List<SongEntry> songs = game.library.songs();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP -> move(-1, songs.size());
            case KeyEvent.VK_DOWN -> move(1, songs.size());
            case KeyEvent.VK_LEFT -> { if (diff > 0) { diff--; game.ui(); } }
            case KeyEvent.VK_RIGHT -> { if (diff < 3) { diff++; game.ui(); } }
            case KeyEvent.VK_F3 -> { game.settings.scrollSpeed = Settings.clamp(game.settings.scrollSpeed - 1, 10, 40); game.toast("Scroll speed " + game.settings.scrollSpeed); }
            case KeyEvent.VK_F4 -> { game.settings.scrollSpeed = Settings.clamp(game.settings.scrollSpeed + 1, 10, 40); game.toast("Scroll speed " + game.settings.scrollSpeed); }
            case KeyEvent.VK_TAB -> { game.settings.autoplay = !game.settings.autoplay; game.toast("Autoplay " + (game.settings.autoplay ? "ON" : "OFF")); }
            case KeyEvent.VK_F5 -> {
                game.library.rescan();
                sel = Math.max(0, Math.min(sel, game.library.songs().size() - 1));
                selAbs = sel;
                scroll = sel;
                game.toast("Found " + game.library.songs().size() + " songs");
            }
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                SongEntry s = current();
                if (s != null && s.ready) {
                    game.settings.lastSong = sel;
                    game.settings.lastDiff = diff;
                    game.settings.save();
                    game.ui();
                    game.setScreen(new PlayScreen(game, s, Difficulty.values()[diff]));
                } else game.toast("Still loading…");
            }
            case KeyEvent.VK_ESCAPE -> { game.ui(); game.setScreen(new TitleScreen(game)); }
            default -> { }
        }
    }

    private void move(int d, int n) {
        if (n == 0) return;
        selAbs += d;
        sel = (int) Math.floorMod(selAbs, (long) n);
        previewDelay = 0.25;
        game.ui();
    }
}
