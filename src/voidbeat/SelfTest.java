package voidbeat;

import javax.sound.sampled.*;
import java.io.*;
import java.util.Locale;
import java.awt.Graphics2D;

/** Headless check: renders every song, prints chart statistics, optionally exports WAVs. */
final class SelfTest {
    private SelfTest() {}

    static void run(String[] args) throws Exception {
        if (java.util.Arrays.asList(args).contains("--sim")) { simulate(); return; }
        if (java.util.Arrays.asList(args).contains("--bench")) { bench(); return; }
        boolean export = false;
        for (String a : args) if (a.equals("--export")) export = true;
        SongLibrary lib = new SongLibrary();
        for (SongEntry e : lib.songs()) {
            lib.await(e);
            if (!e.ready) { System.out.println(e.title + ": " + e.error); continue; }
            double peak = 0, sq = 0;
            for (short s : e.audio) { peak = Math.max(peak, Math.abs(s)); sq += (double) s * s; }
            System.out.printf(Locale.ROOT, "%s - %s | %.2f BPM (first beat %.3fs) | %.0fs | peak %.2f rms %.3f%n", e.artist, e.title, e.bpm, e.beatOffset,
                    e.lengthSec, peak / 32768, Math.sqrt(sq / e.audio.length) / 32768);
            for (Difficulty d : Difficulty.values()) {
                Chart c = e.chart(d);
                int chords = 0;
                double minGap = 1e9;
                for (int i = 1; i < c.notes.size(); i++) {
                    double g = c.notes.get(i).time - c.notes.get(i - 1).time;
                    if (g < 1) chords++; else minGap = Math.min(minGap, g);
                }
                int[] lanes = new int[4];
                for (Chart.Note n : c.notes) lanes[n.lane]++;
                System.out.printf(Locale.ROOT, "   %-6s %4d notes %3d holds %3d chord-notes  min gap %4.0fms  %.2f*  lanes %d/%d/%d/%d%n",
                        d.label, c.notes.size(), c.holdCount(), chords, minGap, c.stars, lanes[0], lanes[1], lanes[2], lanes[3]);
                // overlap sanity: no note may start inside a hold on the same lane
                double[] busy = new double[4];
                java.util.Arrays.fill(busy, -1e9);
                for (Chart.Note n : c.notes) {
                    if (n.time < busy[n.lane]) System.out.println("      !! overlap lane " + n.lane + " @" + n.time);
                    busy[n.lane] = Math.max(busy[n.lane], n.end);
                }
            }
            if (export) {
                File f = new File(e.id.replace(':', '_') + ".wav");
                byte[] b = new byte[e.audio.length * 2];
                for (int i = 0; i < e.audio.length; i++) { b[2 * i] = (byte) e.audio[i]; b[2 * i + 1] = (byte) (e.audio[i] >> 8); }
                AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
                AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(b), fmt, e.audio.length / 2),
                        AudioFileFormat.Type.WAVE, f);
                System.out.println("   exported " + f.getAbsolutePath());
            }
        }
    }

    /** Plays every chart with a simulated imperfect human and checks the judge's bookkeeping. */
    static void simulate() throws Exception {
        Game game = new Game();
        game.settings.autoplay = false;
        game.settings.hitsounds = false;
        for (SongEntry e : game.library.songs()) game.library.await(e);
        java.util.Random r = new java.util.Random(1);
        for (double sigma : new double[]{18, 35}) {
            System.out.println("--- simulated player, timing sigma " + sigma + " ms, 2% skipped notes");
            for (SongEntry e : game.library.songs()) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-17s", e.title));
                for (Difficulty d : Difficulty.values()) {
                    PlayScreen ps = new PlayScreen(game, e, d);
                    java.util.List<double[]> evs = new java.util.ArrayList<>(); // time, lane, type(0 press,1 release)
                    for (Chart.Note n : e.chart(d).notes) {
                        if (r.nextDouble() < 0.02) continue;
                        double pt = n.time + r.nextGaussian() * sigma;
                        evs.add(new double[]{pt, n.lane, 0});
                        double rt = n.hold ? n.end + r.nextGaussian() * sigma * 1.3 - 10 : pt + 60;
                        evs.add(new double[]{Math.max(pt + 20, rt), n.lane, 1});
                    }
                    evs.sort((a, b) -> Double.compare(a[0], b[0]));
                    for (double[] ev : evs) {
                        ps.simulateTo(ev[0]);
                        if (ev[2] == 0) ps.press((int) ev[1], ev[0]); else ps.release((int) ev[1], ev[0]);
                    }
                    ps.simulateTo(e.chart(d).lastTime() + 2000);
                    boolean ok = ps.judged == ps.total;
                    line.append(String.format(Locale.ROOT, " | %s %6.2f%% x%-4d%s", d.label.charAt(0), ps.finalAccuracy(), ps.maxCombo, ok ? "" : " !!" + ps.judged + "/" + ps.total));
                }
                System.out.println(line);
            }
        }
        System.exit(0);
    }

    static void bench() throws Exception {
        Game game = new Game();
        for (SongEntry e : game.library.songs()) game.library.await(e);
        game.settings.autoplay = true;
        java.awt.image.BufferedImage img = game.getGraphicsConfiguration() != null
                ? game.getGraphicsConfiguration().createCompatibleImage(1280, 720)
                : new java.awt.image.BufferedImage(1280, 720, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Game.Screen[] screens = {new TitleScreen(game), new SongSelectScreen(game), null};
        SongEntry s = game.library.songs().get(1);
        for (int k = 0; k < 3; k++) {
            Game.Screen sc = screens[k];
            if (sc == null) { PlayScreen ps = new PlayScreen(game, s, Difficulty.EXPERT); game.setScreen(ps); ps.debugSeek(s.previewSec * 1000 + 5000); sc = ps; }
            else game.setScreen(sc);
            long t0 = 0;
            for (int i = 0; i < 400; i++) {
                if (i == 100) t0 = System.nanoTime();
                game.tick(1 / 240.0);
                Graphics2D g = img.createGraphics();
                game.renderFrame(g);
                g.dispose();
                if (k == 2) Thread.sleep(2);
            }
            System.out.printf(Locale.ROOT, "%-16s %.2f ms/frame (incl. %s)%n", sc.getClass().getSimpleName(), (System.nanoTime() - t0) / 300 / 1e6, k == 2 ? "2ms sleep" : "update");
        }
        System.exit(0);
    }
}
