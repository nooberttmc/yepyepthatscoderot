package voidbeat;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** The main panel: owns the game loop, the shared services and the active screen. */
final class Game extends JPanel {
    static final int W = 1280, H = 720;

    abstract static class Screen {
        final Game game;
        Screen(Game g) { game = g; }
        void enter() { }
        void update(double dt) { }
        abstract void render(Graphics2D g);
        void keyPressed(KeyEvent e) { }
        void keyReleased(KeyEvent e) { }
    }

    final AudioEngine audio = new AudioEngine();
    final Settings settings = new Settings();
    final SongLibrary library = new SongLibrary();
    final Background bg = new Background(audio);
    private Screen screen;
    private double fadeIn = 1;
    private JFrame frame;
    private volatile boolean running = true;
    private double fps, fpsAcc;
    private int fpsFrames;
    boolean showFps;
    private String toast;
    private double toastTime;

    Game() {
        setPreferredSize(new Dimension(W, H));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        applyVolumes();
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_F11) { toggleFullscreen(); return; }
                if (e.getKeyCode() == KeyEvent.VK_F12) { showFps = !showFps; return; }
                if (screen != null) screen.keyPressed(e);
            }
            @Override public void keyReleased(KeyEvent e) {
                if (screen != null) screen.keyReleased(e);
            }
        });
        setScreen(new TitleScreen(this));
    }

    void applyVolumes() {
        audio.musicVol = settings.musicVol / 100f;
        audio.sfxVol = settings.sfxVol / 100f;
    }

    void setScreen(Screen s) {
        screen = s;
        fadeIn = 1;
        s.enter();
    }

    void toast(String msg) { toast = msg; toastTime = 1.6; }

    void ui() { audio.play(audio.uiSound, 1f, 0f); }

    volatile int focusLosses;

    void attach(JFrame f) {
        frame = f;
        f.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { focusLosses++; }
        });
    }

    private boolean fullscreen;

    void toggleFullscreen() {
        if (frame == null) return;
        GraphicsDevice dev = frame.getGraphicsConfiguration().getDevice();
        fullscreen = !fullscreen;
        frame.dispose();
        frame.setUndecorated(fullscreen);
        if (fullscreen && dev.isFullScreenSupported()) dev.setFullScreenWindow(frame);
        else {
            dev.setFullScreenWindow(null);
            frame.setExtendedState(fullscreen ? JFrame.MAXIMIZED_BOTH : JFrame.NORMAL);
            frame.pack();
            frame.setLocationRelativeTo(null);
        }
        frame.setVisible(true);
        requestFocusInWindow();
    }

    void quit() {
        running = false;
        settings.save();
        audio.shutdown();
        System.exit(0);
    }

    // ------------------------------------------------------------------ loop
    void startLoop() {
        Thread t = new Thread(() -> {
            long last = System.nanoTime();
            final long frameNs = 1_000_000_000L / 240;
            while (running) {
                long now = System.nanoTime();
                double dt = Math.min(0.05, (now - last) / 1e9);
                last = now;
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        tick(dt);
                        paintImmediately(0, 0, getWidth(), getHeight());
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Toolkit.getDefaultToolkit().sync();
                long spent = System.nanoTime() - now;
                long sleep = frameNs - spent;
                try {
                    if (sleep > 0) Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                    else Thread.sleep(0, 200_000);
                } catch (InterruptedException ignored) { }
            }
        }, "voidbeat-loop");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY - 1);
        t.start();
    }

    void tick(double dt) {
        bg.update(dt);
        if (screen != null) screen.update(dt);
        fadeIn = Math.max(0, fadeIn - dt * 4);
        toastTime -= dt;
        fpsAcc += dt; fpsFrames++;
        if (fpsAcc >= 0.5) { fps = fpsFrames / fpsAcc; fpsAcc = 0; fpsFrames = 0; }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, getWidth(), getHeight());
        double sc = Math.min(getWidth() / (double) W, getHeight() / (double) H);
        double ox = (getWidth() - W * sc) / 2, oy = (getHeight() - H * sc) / 2;
        g.translate(ox, oy);
        g.scale(sc, sc);
        g.clipRect(0, 0, W, H);
        renderFrame(g);
        g.dispose();
    }

    void renderFrame(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform base = g.getTransform();
        if (screen != null) screen.render(g);
        g.setTransform(base);
        if (toastTime > 0 && toast != null) {
            double a = Math.min(1, toastTime * 3);
            g.setFont(Theme.font(Font.BOLD, 18));
            int tw = g.getFontMetrics().stringWidth(toast) + 40;
            g.setColor(Theme.alpha(new Color(20, 8, 40), 0.9 * a));
            g.fillRoundRect(W / 2 - tw / 2, 24, tw, 40, 20, 20);
            g.setColor(Theme.alpha(Theme.VIOLET, a));
            g.drawRoundRect(W / 2 - tw / 2, 24, tw, 40, 20, 20);
            g.setColor(Theme.alpha(Theme.TEXT, a));
            Theme.drawCentered(g, toast, W / 2.0, 50);
        }
        if (showFps) {
            g.setFont(Theme.font(Font.PLAIN, 12));
            g.setColor(Theme.TEXT_DIM);
            g.drawString(String.format("%.0f fps  |  audio buffer %d ms%s", fps, audio.outputLatencyMs(),
                    audio.hasAudio() ? "" : "  (no audio device)"), 8, H - 8);
        }
        if (fadeIn > 0) {
            g.setColor(new Color(0, 0, 0, (int) (fadeIn * 255)));
            g.fillRect(0, 0, W, H);
        }
    }

    /** Renders the current screen into an image (used for headless screenshots). */
    BufferedImage snapshot() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        double f = fadeIn;
        fadeIn = 0;
        renderFrame(g);
        fadeIn = f;
        g.dispose();
        return img;
    }

    Screen screen() { return screen; }
}
