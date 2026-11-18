package voidbeat;

import java.awt.*;

/** Black + purple palette with blue/cyan accents. */
final class Theme {
    private Theme() {}

    static final Color BG          = new Color(0x06030c);
    static final Color BG2         = new Color(0x120722);
    static final Color PANEL       = new Color(12, 6, 24, 215);
    static final Color PANEL_EDGE  = new Color(0x3b1d6e);
    static final Color PURPLE      = new Color(0xa855f7);
    static final Color PURPLE_DEEP = new Color(0x6d28d9);
    static final Color VIOLET      = new Color(0xc084fc);
    static final Color LAVENDER    = new Color(0xe9d5ff);
    static final Color BLUE        = new Color(0x38bdf8);
    static final Color CYAN        = new Color(0x22d3ee);
    static final Color PINK        = new Color(0xf472b6);
    static final Color GOLD        = new Color(0xfbbf24);
    static final Color TEXT        = new Color(0xf3e8ff);
    static final Color TEXT_DIM    = new Color(0x9d8cbf);
    static final Color TEXT_FAINT  = new Color(0x5b4b7a);

    /** Lane colours: outer lanes purple, inner lanes blue (classic 4K look). */
    static Color lane(int lane) {
        return (lane == 0 || lane == 3) ? PURPLE : BLUE;
    }

    static Color lerp(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    static Color alpha(Color c, double a) {
        int al = (int) Math.max(0, Math.min(255, a * 255));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), al);
    }

    private static final String FAMILY = pickFamily();

    private static String pickFamily() {
        String[] prefs = {"Montserrat", "Poppins", "Inter", "Segoe UI", "Helvetica Neue", "DejaVu Sans", "SansSerif"};
        try {
            java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String p : prefs) if (have.contains(p)) return p;
        } catch (Throwable ignored) { }
        return Font.SANS_SERIF;
    }

    private static final java.util.Map<Integer, Font> CACHE = new java.util.HashMap<>();

    static Font font(int style, float size) {
        int key = style * 10000 + Math.round(size * 10);
        return CACHE.computeIfAbsent(key, k -> new Font(FAMILY, style, 1).deriveFont(style, size));
    }

    static void drawCentered(Graphics2D g, String s, double cx, double y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) y);
    }

    static void drawRight(Graphics2D g, String s, double rx, double y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (rx - fm.stringWidth(s)), (float) y);
    }

    /** Text with a soft coloured glow behind it (cached as a pre-blurred sprite). Alpha comes from core's alpha. */
    static void glowText(Graphics2D g, String s, double cx, double y, Color core, Color glow, int radius) {
        Font f = g.getFont();
        String key = s + '\u0001' + f.getStyle() + '\u0001' + f.getSize2D() + '\u0001' + (core.getRGB() & 0xffffff)
                + '\u0001' + (glow.getRGB() & 0xffffff) + '\u0001' + radius;
        Sprite sp = GLOW.get(key);
        if (sp == null) { sp = makeGlow(s, f, core, glow, radius); GLOW.put(key, sp); }
        float a = core.getAlpha() / 255f;
        Composite old = g.getComposite();
        if (a < 0.999f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, a)));
        g.drawImage(sp.img, (int) Math.round(cx - sp.textW / 2.0) - sp.pad, (int) Math.round(y) - sp.baseline, null);
        g.setComposite(old);
    }

    private record Sprite(java.awt.image.BufferedImage img, int pad, int baseline, int textW) {}

    private static final java.util.Map<String, Sprite> GLOW = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Sprite> e) { return size() > 300; }
    };

    private static Sprite makeGlow(String s, Font f, Color core, Color glow, int radius) {
        java.awt.image.BufferedImage tmp = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = tmp.createGraphics();
        tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        FontMetrics fm = tg.getFontMetrics(f);
        tg.dispose();
        int r = Math.max(1, radius) * 2;
        int pad = r * 2 + 2;
        int tw = Math.max(1, fm.stringWidth(s));
        int w = tw + pad * 2, h = fm.getAscent() + fm.getDescent() + pad * 2;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(f);
        g.setColor(Color.WHITE);
        int base = pad + fm.getAscent();
        g.drawString(s, pad, base);
        g.dispose();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] al = new int[w * h];
        for (int i = 0; i < px.length; i++) al[i] = px[i] >>> 24;
        for (int pass = 0; pass < 3; pass++) { boxBlur(al, w, h, r / 2 + 1, true); boxBlur(al, w, h, r / 2 + 1, false); }
        int gr = glow.getRGB() & 0xffffff;
        for (int i = 0; i < px.length; i++) {
            int a = Math.min(255, (int) (al[i] * 1.6));
            px[i] = (a << 24) | gr;
        }
        img.setRGB(0, 0, w, h, px, 0, w);
        g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(f);
        g.setColor(new Color(core.getRGB() & 0xffffff));
        g.drawString(s, pad, base);
        g.dispose();
        return new Sprite(img, pad, base, tw);
    }

    private static void boxBlur(int[] a, int w, int h, int r, boolean horiz) {
        int n = horiz ? w : h, m = horiz ? h : w;
        int[] line = new int[n];
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) line[i] = a[horiz ? j * w + i : i * w + j];
            int sum = 0, win = 2 * r + 1;
            for (int i = -r; i <= r; i++) sum += line[Math.max(0, Math.min(n - 1, i))];
            for (int i = 0; i < n; i++) {
                a[horiz ? j * w + i : i * w + j] = sum / win;
                sum += line[Math.min(n - 1, i + r + 1)] - line[Math.max(0, i - r)];
            }
        }
    }
}
