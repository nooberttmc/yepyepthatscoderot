package voidbeat;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;

final class SettingsScreen extends Game.Screen {
    private final Game.Screen back;
    private int sel;
    private static final String[] NAMES = {"Scroll speed", "Audio offset", "Hitsounds", "Music volume",
            "Effects volume", "Hit error bar", "Back"};

    SettingsScreen(Game g, Game.Screen back) { super(g); this.back = back; }

    private String value(int i) {
        Settings s = game.settings;
        return switch (i) {
            case 0 -> s.scrollSpeed + "   (" + Math.round(s.travelMs()) + " ms on screen)";
            case 1 -> (s.offsetMs > 0 ? "+" : "") + s.offsetMs + " ms";
            case 2 -> s.hitsounds ? "ON" : "OFF";
            case 3 -> s.musicVol + "%";
            case 4 -> s.sfxVol + "%";
            case 5 -> s.showErrorBar ? "ON" : "OFF";
            default -> "";
        };
    }

    private String help(int i) {
        return switch (i) {
            case 0 -> "Higher = notes fall faster and are spaced further apart. Also F3 / F4 in-game.";
            case 1 -> "If you keep hitting LATE (results show +ms), raise this. Hitting EARLY: lower it. Also - / = in-game.";
            case 2 -> "A crisp tick on every key press.";
            case 5 -> "Shows each hit's timing under the receptors (left = early, right = late).";
            default -> "";
        };
    }

    private void change(int dir) {
        Settings s = game.settings;
        switch (sel) {
            case 0 -> s.scrollSpeed = Settings.clamp(s.scrollSpeed + dir, 10, 40);
            case 1 -> s.offsetMs = Settings.clamp(s.offsetMs + dir * 5, -300, 300);
            case 2 -> s.hitsounds = !s.hitsounds;
            case 3 -> s.musicVol = Settings.clamp(s.musicVol + dir * 5, 0, 100);
            case 4 -> s.sfxVol = Settings.clamp(s.sfxVol + dir * 5, 0, 100);
            case 5 -> s.showErrorBar = !s.showErrorBar;
            default -> { return; }
        }
        game.applyVolumes();
        game.ui();
    }

    @Override void render(Graphics2D g) {
        game.bg.render(g, 0.35);
        double cx = Game.W / 2.0;
        g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 52));
        Theme.glowText(g, "SETTINGS", cx, 110, Theme.TEXT, Theme.PURPLE, 5);
        double x = cx - 360, w = 720;
        g.setColor(Theme.PANEL);
        g.fill(new RoundRectangle2D.Double(x, 150, w, 440, 24, 24));
        g.setColor(Theme.PANEL_EDGE);
        g.draw(new RoundRectangle2D.Double(x, 150, w, 440, 24, 24));
        for (int i = 0; i < NAMES.length; i++) {
            double y = 200 + i * 54;
            boolean s = i == sel;
            if (s) {
                g.setPaint(new GradientPaint((float) x, 0, Theme.alpha(Theme.PURPLE, 0.35), (float) (x + w), 0, Theme.alpha(Theme.BLUE, 0.12)));
                g.fill(new RoundRectangle2D.Double(x + 14, y - 30, w - 28, 44, 16, 16));
            }
            g.setFont(Theme.font(Font.BOLD, 22));
            g.setColor(s ? Color.WHITE : Theme.TEXT_DIM);
            g.drawString(NAMES[i], (float) (x + 40), (float) y);
            String v = value(i);
            if (!v.isEmpty()) {
                g.setColor(s ? Theme.CYAN : Theme.VIOLET);
                Theme.drawRight(g, (s ? "◀  " : "") + v + (s ? "  ▶" : ""), x + w - 40, y);
            }
        }
        g.setFont(Theme.font(Font.PLAIN, 16));
        g.setColor(Theme.TEXT_DIM);
        Theme.drawCentered(g, help(sel), cx, 625);
        g.setColor(Theme.TEXT_FAINT);
        g.setFont(Theme.font(Font.PLAIN, 14));
        Theme.drawCentered(g, "↑↓ select   •   ←→ change   •   ESC back", cx, 680);
    }

    @Override void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP -> { sel = (sel + NAMES.length - 1) % NAMES.length; game.ui(); }
            case KeyEvent.VK_DOWN -> { sel = (sel + 1) % NAMES.length; game.ui(); }
            case KeyEvent.VK_LEFT -> change(-1);
            case KeyEvent.VK_RIGHT -> change(1);
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                if (sel == NAMES.length - 1) close(); else change(1);
            }
            case KeyEvent.VK_ESCAPE -> close();
            default -> { }
        }
    }

    private void close() {
        game.settings.save();
        game.ui();
        game.setScreen(back);
    }
}
