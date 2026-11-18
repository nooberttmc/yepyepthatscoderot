package voidbeat;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.*;

final class TitleScreen extends Game.Screen {
    private static final String[] ITEMS = {"PLAY", "SETTINGS", "QUIT"};
    private int sel;
    private double t;
    private final PulseTracker pulses = new PulseTracker();
    private SongEntry music;
    private final double[] keyGlow = new double[4];
    private int beatCount;
    private double selAnim;

    TitleScreen(Game g) { super(g); }

    @Override void enter() { music = null; }

    @Override void update(double dt) {
        t += dt;
        selAnim += (sel - selAnim) * Math.min(1, dt * 18);
        if (music == null || !game.audio.isPlaying()) {
            var songs = game.library.songs();
            SongEntry pick = songs.size() > 1 ? songs.get(1) : songs.isEmpty() ? null : songs.get(0);
            if (pick != null && pick.ready) {
                music = pick;
                game.audio.playPreview(pick.audio, pick.previewSec, 32);
            }
        }
        if (music != null && pulses.update(music, game.audio.nowMs() / 1000.0, game.bg)) {
            keyGlow[beatCount++ % 4] = 1;
        }
        for (int i = 0; i < 4; i++) keyGlow[i] *= Math.exp(-dt * 5);
    }

    @Override void render(Graphics2D g) {
        game.bg.render(g, 0);
        double p = game.bg.pulse();
        double cx = Game.W / 2.0, cy = 230;

        // rings
        Stroke os = g.getStroke();
        for (int i = 0; i < 3; i++) {
            double r = 150 + i * 55 + p * 18 + Math.sin(t * 1.3 + i) * 6;
            g.setStroke(new BasicStroke(2f - i * 0.5f));
            g.setColor(Theme.alpha(i == 1 ? Theme.BLUE : Theme.PURPLE, 0.25 - i * 0.06 + p * 0.25));
            g.draw(new Ellipse2D.Double(cx - r, cy - r * 0.42, r * 2, r * 0.84));
        }
        g.setStroke(os);

        // logo
        g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 118));
        Theme.glowText(g, "VOIDBEAT", cx, cy + 40, Theme.TEXT, Theme.PURPLE, 6 + (int) (p * 6));
        g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 118));
        FontMetrics fm = g.getFontMetrics();
        int lw = fm.stringWidth("VOIDBEAT");
        g.setPaint(new GradientPaint((float) (cx - lw / 2.0), 0, Theme.LAVENDER, (float) (cx + lw / 2.0), 0, Theme.BLUE));
        g.drawString("VOIDBEAT", (float) (cx - lw / 2.0), (float) (cy + 40));
        g.setFont(Theme.font(Font.BOLD, 16));
        g.setColor(Theme.alpha(Theme.VIOLET, 0.9));
        Theme.drawCentered(g, "4 K E Y   R H Y T H M", cx, cy + 78);

        // D F J K keys
        String[] keys = {"D", "F", "J", "K"};
        for (int i = 0; i < 4; i++) {
            double x = cx - 2 * 62 + i * 62 + 4, y = 340;
            Color c = Theme.lane(i);
            double gl = keyGlow[i];
            g.setColor(Theme.alpha(c, 0.12 + gl * 0.5));
            g.fill(new RoundRectangle2D.Double(x, y, 54, 54, 14, 14));
            g.setColor(Theme.alpha(c, 0.6 + gl * 0.4));
            g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Double(x, y, 54, 54, 14, 14));
            g.setFont(Theme.font(Font.BOLD, 22));
            g.setColor(Theme.lerp(Theme.TEXT_DIM, Color.WHITE, gl));
            Theme.drawCentered(g, keys[i], x + 27, y + 35);
        }
        g.setStroke(os);

        // menu
        double my = 470;
        double hy = my + selAnim * 54;
        g.setPaint(new GradientPaint((float) (cx - 170), 0, new Color(0, 0, 0, 0), (float) cx, 0, Theme.alpha(Theme.PURPLE, 0.35), true));
        g.fill(new RoundRectangle2D.Double(cx - 170, hy - 30, 340, 44, 22, 22));
        for (int i = 0; i < ITEMS.length; i++) {
            boolean s = i == sel;
            g.setFont(Theme.font(Font.BOLD, s ? 28 : 24));
            g.setColor(s ? Color.WHITE : Theme.TEXT_DIM);
            Theme.drawCentered(g, ITEMS[i], cx, my + i * 54);
        }

        g.setFont(Theme.font(Font.PLAIN, 14));
        g.setColor(Theme.TEXT_DIM);
        Theme.drawCentered(g, "↑↓ select   •   ENTER confirm   •   F11 fullscreen", cx, 670);
        g.setColor(Theme.alpha(Theme.TEXT_DIM, 0.75));
        g.setFont(Theme.font(Font.PLAIN, 12));
        Theme.drawCentered(g, "All built-in music is synthesized live in code — 100% original & copyright-free.  Add your own tracks: drop .wav files in /songs",
                cx, 696);
        if (music == null) {
            g.setColor(Theme.TEXT_DIM);
            Theme.drawCentered(g, "composing soundtrack" + ".".repeat(1 + (int) (t * 3) % 3), cx, 420);
        }
    }

    @Override void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP, KeyEvent.VK_F, KeyEvent.VK_D -> { sel = (sel + ITEMS.length - 1) % ITEMS.length; game.ui(); }
            case KeyEvent.VK_DOWN, KeyEvent.VK_J, KeyEvent.VK_K -> { sel = (sel + 1) % ITEMS.length; game.ui(); }
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                game.ui();
                switch (sel) {
                    case 0 -> game.setScreen(new SongSelectScreen(game));
                    case 1 -> game.setScreen(new SettingsScreen(game, this));
                    default -> game.quit();
                }
            }
            case KeyEvent.VK_ESCAPE -> game.quit();
            default -> { }
        }
    }
}
