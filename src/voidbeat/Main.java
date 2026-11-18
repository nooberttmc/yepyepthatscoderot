package voidbeat;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

/**
 * VOIDBEAT - a 4-key vertical rhythm game in Java Swing.
 * Controls: D F J K. Run with --selftest to check song rendering/charts headlessly.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        if (args.length > 0 && args[0].equals("--selftest")) { SelfTest.run(args); return; }
        if (args.length > 1 && args[0].equals("--shots")) { shots(new File(args[1])); return; }

        SwingUtilities.invokeAndWait(() -> {
            Game game = new Game();
            JFrame f = new JFrame("VOIDBEAT");
            f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { game.quit(); }
            });
            f.setBackground(Color.BLACK);
            f.getContentPane().setBackground(Color.BLACK);
            f.add(game);
            f.pack();
            f.setMinimumSize(new Dimension(640, 400));
            f.setLocationRelativeTo(null);
            game.attach(f);
            f.setVisible(true);
            game.requestFocusInWindow();
            game.startLoop();
        });
    }

    /** Headless screenshot tour used for testing the visuals. */
    private static void shots(File dir) throws Exception {
        dir.mkdirs();
        Game[] gh = new Game[1];
        SwingUtilities.invokeAndWait(() -> gh[0] = new Game());
        Game game = gh[0];
        game.startLoop();
        for (SongEntry s : game.library.songs()) game.library.await(s);
        Thread.sleep(2500);
        save(game, dir, "1-title");
        SwingUtilities.invokeAndWait(() -> game.setScreen(new SongSelectScreen(game)));
        Thread.sleep(1500);
        save(game, dir, "2-select");
        game.settings.autoplay = true;
        SongEntry song = game.library.songs().get(3);
        PlayScreen[] ps = new PlayScreen[1];
        SwingUtilities.invokeAndWait(() -> {
            ps[0] = new PlayScreen(game, song, Difficulty.HARD);
            game.setScreen(ps[0]);
            ps[0].debugSeek(song.previewSec * 1000 + 6000);
        });
        Thread.sleep(2600);
        save(game, dir, "3-play-hard");
        SwingUtilities.invokeAndWait(() -> {
            ps[0] = new PlayScreen(game, game.library.songs().get(1), Difficulty.EXPERT);
            game.setScreen(ps[0]);
            ps[0].debugSeek(game.library.songs().get(1).previewSec * 1000 + 15000);
        });
        Thread.sleep(3100);
        save(game, dir, "4-play-expert");
        // play the last seconds of a song for real so the game reaches the results screen on its own
        SongEntry last = game.library.songs().get(4);
        SwingUtilities.invokeAndWait(() -> {
            PlayScreen p = new PlayScreen(game, last, Difficulty.NORMAL);
            game.setScreen(p);
            p.debugSeek(last.chart(Difficulty.NORMAL).lastTime() - 2500);
        });
        long deadline = System.currentTimeMillis() + 15000;
        while (!(game.screen() instanceof ResultsScreen) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        System.out.println("reached results: " + (game.screen() instanceof ResultsScreen));
        Thread.sleep(1500);
        save(game, dir, "5-results");
        System.exit(0);
    }

    private static void save(Game game, File dir, String name) throws Exception {
        java.awt.image.BufferedImage[] img = new java.awt.image.BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> img[0] = game.snapshot());
        ImageIO.write(img[0], "png", new File(dir, name + ".png"));
        System.out.println("saved " + name);
    }
}
