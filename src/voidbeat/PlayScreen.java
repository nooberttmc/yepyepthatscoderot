package voidbeat;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class PlayScreen extends Game.Screen {
    static final int LANES = 4, LANE_W = 96, FIELD_W = LANES * LANE_W, FIELD_X = (Game.W - FIELD_W) / 2;
    static final int HIT_Y = 596, NOTE_H = 28, PAD = 14;
    static final int[] KEYS = {KeyEvent.VK_D, KeyEvent.VK_F, KeyEvent.VK_J, KeyEvent.VK_K};
    static final String[] KEY_NAMES = {"D", "F", "J", "K"};

    final SongEntry song;
    final Difficulty diff;
    final Chart chart;
    private final boolean auto;
    private final double travel;
    private final double offset;

    @SuppressWarnings("unchecked")
    private final List<Chart.Note>[] lanes = new List[LANES];
    private final int[] judgeIdx = new int[LANES], drawIdx = new int[LANES];
    private final Chart.Note[] holding = new Chart.Note[LANES];
    private final boolean[] down = new boolean[LANES];
    private final long[] pendingRel = new long[LANES];
    private final double[] pendingRelT = new double[LANES];
    private final double[] autoRelease = new double[LANES];
    private final double[] beam = new double[LANES], flash = new double[LANES];
    private final double[] holdSpark = new double[LANES];

    private double now = -3000;     // judged time (ms)
    private double audioMs;
    private double t;
    private boolean paused;
    private int pauseSel;
    private double finishTimer = -1;
    private String banner;

    final int[] counts = new int[6];
    int combo, maxCombo, judged;
    final int total;
    private long scoreSum, accSum;
    final List<Double> errors = new ArrayList<>();

    // error meter
    private final double[] tickErr = new double[48], tickAge = new double[48];
    private final Judgement[] tickJ = new Judgement[48];
    private int tickHead;
    private double avgErr;

    // judgement display
    private Judgement lastJ;
    private double jAge = 10, lastErr;
    private boolean showEL;
    private double comboAnim, milestone, missFlash;

    private final List<double[]> parts = new ArrayList<>();   // x,y,vx,vy,life,max,size,r,g,b
    private final List<double[]> rings = new ArrayList<>();   // x,y,age,r,g,b,scale
    private final Random rng = new Random();
    private final PulseTracker pulses = new PulseTracker();
    private final BufferedImage[] noteImg = new BufferedImage[LANES];
    private final double firstNote;
    private int seenFocusLosses;

    PlayScreen(Game g, SongEntry song, Difficulty diff) {
        super(g);
        this.song = song;
        this.diff = diff;
        this.chart = song.chart(diff);
        this.auto = g.settings.autoplay;
        this.travel = g.settings.travelMs();
        this.offset = g.settings.offsetMs;
        chart.reset();
        for (int i = 0; i < LANES; i++) lanes[i] = new ArrayList<>();
        for (Chart.Note n : chart.notes) lanes[n.lane].add(n);
        total = chart.judgementCount();
        firstNote = chart.notes.isEmpty() ? 0 : chart.notes.get(0).time;
        for (int i = 0; i < LANES; i++) noteImg[i] = makeNote(Theme.lane(i));
        seenFocusLosses = g.focusLosses;
    }

    @Override void enter() {
        double startSec = Math.min(0, firstNote / 1000.0 - 2.6);
        game.audio.playSong(song.audio, startSec);
        now = startSec * 1000;
    }

    /** Testing aid: advance judgement to the given time without the audio clock. */
    void simulateTo(double ms) {
        now = ms;
        for (int l = 0; l < LANES; l++) judgeLane(l);
    }

    /** Testing aid: jump into the song, counting everything before as perfectly hit. */
    void debugSeek(double ms) {
        for (Chart.Note n : chart.notes) {
            if (n.end >= ms - 50) continue;
            n.headDone = true; n.head = Judgement.MARVELOUS;
            apply(Judgement.MARVELOUS, n, false, 0);
            if (n.hold) { n.tailDone = true; n.tail = Judgement.MARVELOUS; apply(Judgement.MARVELOUS, n, true, 0); }
        }
        parts.clear();
        rings.clear();
        game.audio.playSong(song.audio, ms / 1000.0);
        now = ms;
    }

    private static BufferedImage makeNote(Color c) {
        int w = LANE_W - 10, h = NOTE_H;
        BufferedImage img = new BufferedImage(w + PAD * 2, h + PAD * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = PAD; i >= 1; i--) {
            g.setColor(Theme.alpha(c, 0.018 * (PAD - i + 1) * 0.6));
            g.fill(new RoundRectangle2D.Double(PAD - i, PAD - i, w + 2 * i, h + 2 * i, 14 + i, 14 + i));
        }
        g.setPaint(new GradientPaint(0, PAD, Theme.lerp(c, Color.WHITE, 0.45), 0, PAD + h, Theme.lerp(c, Color.BLACK, 0.25)));
        g.fill(new RoundRectangle2D.Double(PAD, PAD, w, h, 14, 14));
        g.setColor(Theme.alpha(Color.WHITE, 0.75));
        g.fill(new RoundRectangle2D.Double(PAD + 10, PAD + 4, w - 20, 4, 4, 4));
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(Theme.lerp(c, Color.WHITE, 0.6));
        g.draw(new RoundRectangle2D.Double(PAD + 0.75, PAD + 0.75, w - 1.5, h - 1.5, 13, 13));
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ input
    private int laneOf(int code) {
        for (int i = 0; i < LANES; i++) if (KEYS[i] == code) return i;
        return -1;
    }

    private double judgedNow() { return game.audio.nowMs() - offset; }

    @Override void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();
        if (paused) {
            switch (code) {
                case KeyEvent.VK_UP -> { pauseSel = (pauseSel + 2) % 3; game.ui(); }
                case KeyEvent.VK_DOWN -> { pauseSel = (pauseSel + 1) % 3; game.ui(); }
                case KeyEvent.VK_ESCAPE -> resume();
                case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                    game.ui();
                    if (pauseSel == 0) resume();
                    else if (pauseSel == 1) retry();
                    else quitToSelect();
                }
                default -> { }
            }
            return;
        }
        int lane = laneOf(code);
        if (lane >= 0) {
            if (pendingRel[lane] != 0 && e.getWhen() - pendingRel[lane] <= 4) { pendingRel[lane] = 0; return; }
            if (pendingRel[lane] != 0) flushRelease(lane);
            if (down[lane] || auto) return;
            down[lane] = true;
            press(lane, judgedNow());
            return;
        }
        switch (code) {
            case KeyEvent.VK_ESCAPE -> pause();
            case KeyEvent.VK_BACK_QUOTE -> retry();
            case KeyEvent.VK_F3 -> speed(-1);
            case KeyEvent.VK_F4 -> speed(1);
            case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> nudgeOffset(-5);
            case KeyEvent.VK_EQUALS, KeyEvent.VK_ADD -> nudgeOffset(5);
            default -> { }
        }
    }

    @Override void keyReleased(KeyEvent e) {
        int lane = laneOf(e.getKeyCode());
        if (lane < 0 || !down[lane] || auto) return;
        // defer to filter out X11 auto-repeat (release+press pairs with equal timestamps)
        pendingRel[lane] = e.getWhen();
        pendingRelT[lane] = judgedNow();
    }

    private void flushRelease(int lane) {
        pendingRel[lane] = 0;
        down[lane] = false;
        release(lane, pendingRelT[lane]);
    }

    private void speed(int d) {
        game.settings.scrollSpeed = Settings.clamp(game.settings.scrollSpeed + d, 10, 40);
        game.settings.save();
        game.toast("Scroll speed " + game.settings.scrollSpeed + " (applies on retry)");
    }

    private void nudgeOffset(int d) {
        game.settings.offsetMs = Settings.clamp(game.settings.offsetMs + d, -300, 300);
        game.settings.save();
        game.toast("Offset " + game.settings.offsetMs + " ms (applies on retry)");
    }

    private void pause() {
        if (finishTimer >= 0) return;
        paused = true;
        pauseSel = 0;
        game.audio.setPaused(true);
        for (int l = 0; l < LANES; l++) if (down[l]) { down[l] = false; pendingRel[l] = 0; release(l, now); }
    }

    private void resume() {
        paused = false;
        game.audio.setPaused(false);
    }

    private void retry() {
        game.setScreen(new PlayScreen(game, song, diff));
    }

    private void quitToSelect() {
        game.audio.stop();
        game.setScreen(new SongSelectScreen(game));
    }

    // ------------------------------------------------------------------ judging
    void press(int lane, double tm) {
        beam[lane] = 1;
        if (game.settings.hitsounds) game.audio.play(game.audio.hitSound, 0.9f, (lane - 1.5f) * 0.25f);
        List<Chart.Note> ln = lanes[lane];
        Chart.Note n = null;
        for (int i = judgeIdx[lane]; i < ln.size(); i++) {
            Chart.Note c = ln.get(i);
            if (!c.headDone) { n = c; break; }
        }
        if (n == null) return;
        double err = tm - n.time;
        if (err < -diff.missWindow()) return;
        Judgement j = diff.judge(Math.abs(err), 1);
        if (j == null) {
            if (err < 0 && diff.forgiving) return;
            n.headDone = true;
            n.head = Judgement.MISS;
            n.dead = true;
            apply(Judgement.MISS, n, false, err);
            if (n.hold) { n.tailDone = true; n.tail = Judgement.MISS; apply(Judgement.MISS, n, true, 0); }
            return;
        }
        n.headDone = true;
        n.head = j;
        flash[lane] = 1;
        apply(j, n, false, err);
        if (n.hold) { n.holding = true; holding[lane] = n; }
    }

    void release(int lane, double tm) {
        Chart.Note n = holding[lane];
        if (n == null) return;
        holding[lane] = null;
        n.holding = false;
        if (n.tailDone) return;
        double err = tm - n.end;
        Judgement j = err >= 0 ? Judgement.MARVELOUS : diff.judge(Math.abs(err), 1.5);
        n.tailDone = true;
        if (j == null) {
            n.tail = Judgement.MISS;
            n.dead = true;
            apply(Judgement.MISS, n, true, 0);
        } else {
            n.tail = j;
            flash[lane] = 1;
            apply(j, n, true, 0);
            if (game.settings.hitsounds) game.audio.play(game.audio.holdEndSound, 0.8f, (lane - 1.5f) * 0.25f);
        }
    }

    private void apply(Judgement j, Chart.Note n, boolean tail, double err) {
        counts[j.ordinal()]++;
        scoreSum += j.score;
        accSum += j.acc;
        judged++;
        if (j == Judgement.MISS) {
            if (combo >= 20) game.audio.play(game.audio.missSound, 1f, 0f);
            combo = 0;
            missFlash = 1;
        } else {
            combo++;
            maxCombo = Math.max(maxCombo, combo);
            comboAnim = 1;
            if (combo % 100 == 0) { milestone = 1; game.bg.kick(1.0); }
        }
        if (!tail && j != Judgement.MISS) {
            errors.add(err);
            tickErr[tickHead] = err;
            tickAge[tickHead] = 0;
            tickJ[tickHead] = j;
            tickHead = (tickHead + 1) % tickErr.length;
            avgErr += (err - avgErr) * 0.15;
        }
        lastJ = j;
        jAge = 0;
        lastErr = err;
        showEL = !tail && j != Judgement.MARVELOUS && j != Judgement.MISS;
        if (j != Judgement.MISS) burst(n.lane, j);
    }

    private void burst(int lane, Judgement j) {
        double cx = FIELD_X + lane * LANE_W + LANE_W / 2.0, cy = HIT_Y;
        Color lc = Theme.lane(lane);
        Color c = j == Judgement.MARVELOUS ? Theme.lerp(lc, Color.WHITE, 0.55) : Theme.lerp(lc, j.color, 0.5);
        double scale = switch (j) { case MARVELOUS -> 1.15; case PERFECT -> 1.0; case GREAT -> 0.85; default -> 0.65; };
        rings.add(new double[]{cx, cy, 0, c.getRed(), c.getGreen(), c.getBlue(), scale});
        int count = switch (j) { case MARVELOUS -> 18; case PERFECT -> 13; case GREAT -> 9; case GOOD -> 5; default -> 3; };
        for (int i = 0; i < count; i++) spark(cx + (rng.nextDouble() - 0.5) * 50, cy, c, 1.0);
    }

    private void spark(double x, double y, Color c, double power) {
        double ang = -Math.PI / 2 + (rng.nextDouble() - 0.5) * 2.0;
        double sp = (180 + rng.nextDouble() * 520) * power;
        double life = 0.3 + rng.nextDouble() * 0.35;
        parts.add(new double[]{x, y, Math.cos(ang) * sp, Math.sin(ang) * sp, life, life,
                1.5 + rng.nextDouble() * 3, c.getRed(), c.getGreen(), c.getBlue()});
    }

    // ------------------------------------------------------------------ update
    @Override void update(double dt) {
        t += dt;
        if (!paused) {
            audioMs = game.audio.updateClock();
            now = audioMs - offset;
            if (game.focusLosses != seenFocusLosses) {
                seenFocusLosses = game.focusLosses;
                if (!auto && finishTimer < 0) { pause(); return; }
            }
        }
        pulses.update(song, audioMs / 1000.0, game.bg);

        long wall = System.currentTimeMillis();
        for (int l = 0; l < LANES; l++) if (pendingRel[l] != 0 && wall - pendingRel[l] > 12) flushRelease(l);

        if (!paused) {
            if (auto) autoplay();
            for (int l = 0; l < LANES; l++) judgeLane(l);
        }

        // effects
        for (int l = 0; l < LANES; l++) {
            if (!down[l]) beam[l] = Math.max(0, beam[l] - dt * 7);
            flash[l] *= Math.exp(-dt * 11);
            if (holding[l] != null && !paused) {
                holdSpark[l] += dt;
                while (holdSpark[l] > 0.028) {
                    holdSpark[l] -= 0.028;
                    spark(FIELD_X + l * LANE_W + LANE_W / 2.0 + (rng.nextDouble() - 0.5) * 40, HIT_Y,
                            Theme.lerp(Theme.lane(l), Color.WHITE, 0.4), 0.6);
                }
            }
        }
        for (int i = parts.size() - 1; i >= 0; i--) {
            double[] p = parts.get(i);
            p[4] -= dt;
            if (p[4] <= 0) { parts.remove(i); continue; }
            p[3] += 1400 * dt;
            p[2] *= Math.exp(-dt * 2.5);
            p[0] += p[2] * dt;
            p[1] += p[3] * dt;
        }
        for (int i = rings.size() - 1; i >= 0; i--) {
            double[] r = rings.get(i);
            r[2] += dt;
            if (r[2] > 0.32) rings.remove(i);
        }
        for (int i = 0; i < tickAge.length; i++) tickAge[i] += dt;
        jAge += dt;
        comboAnim *= Math.exp(-dt * 12);
        milestone *= Math.exp(-dt * 2.5);
        missFlash *= Math.exp(-dt * 6);

        // finish
        if (finishTimer < 0 && judged >= total && now > chart.lastTime() + 900) {
            finishTimer = 0;
            if (counts[5] == 0 && total > 0) {
                boolean allMarv = counts[0] == total;
                banner = allMarv ? "ALL MARVELOUS" : (counts[2] + counts[3] + counts[4] == 0 ? "ALL PERFECT" : "FULL COMBO");
                game.bg.kick(1.0);
            }
        }
        if (finishTimer >= 0) {
            finishTimer += dt;
            if (finishTimer > (banner != null ? 2.6 : 1.4)) {
                game.setScreen(new ResultsScreen(game, this));
            }
        }
    }

    private void judgeLane(int l) {
        List<Chart.Note> ln = lanes[l];
        for (int i = judgeIdx[l]; i < ln.size(); i++) {
            Chart.Note n = ln.get(i);
            if (n.time > now + diff.missWindow()) break;
            if (!n.headDone && now - n.time > diff.badWindow()) {
                n.headDone = true;
                n.head = Judgement.MISS;
                n.dead = true;
                apply(Judgement.MISS, n, false, 0);
                if (n.hold) { n.tailDone = true; n.tail = Judgement.MISS; apply(Judgement.MISS, n, true, 0); }
            }
            if (n.hold && n.holding && !n.tailDone && now >= n.end) {
                n.tailDone = true;
                n.holding = false;
                n.tail = Judgement.MARVELOUS;
                holding[l] = null;
                flash[l] = 1;
                apply(Judgement.MARVELOUS, n, true, 0);
                if (game.settings.hitsounds) game.audio.play(game.audio.holdEndSound, 0.8f, (l - 1.5f) * 0.25f);
            }
        }
        while (judgeIdx[l] < ln.size()) {
            Chart.Note n = ln.get(judgeIdx[l]);
            if (n.headDone && (!n.hold || n.tailDone)) judgeIdx[l]++;
            else break;
        }
        // drawing pointer: drop notes fully below the screen
        while (drawIdx[l] < ln.size()) {
            Chart.Note n = ln.get(drawIdx[l]);
            boolean done = n.headDone && (!n.hold || n.tailDone);
            if (done && (!n.dead || yOf(n.end) > Game.H + 60) && (n.dead || !n.hold || !n.holding)) drawIdx[l]++;
            else break;
        }
    }

    private void autoplay() {
        for (int l = 0; l < LANES; l++) {
            if (down[l] && holding[l] == null && now >= autoRelease[l]) down[l] = false;
            if (holding[l] != null && now >= holding[l].end) { release(l, holding[l].end); down[l] = false; }
            List<Chart.Note> ln = lanes[l];
            for (int i = judgeIdx[l]; i < ln.size(); i++) {
                Chart.Note n = ln.get(i);
                if (n.headDone) continue;
                if (n.time <= now) {
                    down[l] = true;
                    press(l, n.time);
                    autoRelease[l] = now + 70;
                }
                break;
            }
        }
    }

    // ------------------------------------------------------------------ render
    private double yOf(double tm) { return HIT_Y - (tm - now) / travel * HIT_Y; }

    int score() { return total == 0 ? 0 : (int) Math.round(1_000_000.0 * scoreSum / (320.0 * total)); }

    double accuracy() { return judged == 0 ? 100 : accSum * 100.0 / (300.0 * judged); }

    double finalAccuracy() { return total == 0 ? 100 : accSum * 100.0 / (300.0 * total); }

    boolean autoplayed() { return auto; }

    @Override void render(Graphics2D g) {
        game.bg.render(g, 0.18);
        double p = game.bg.pulse();

        // playfield
        g.setColor(new Color(4, 2, 10, 225));
        g.fillRect(FIELD_X, 0, FIELD_W, Game.H);
        for (int l = 0; l < LANES; l++) {
            if (l % 2 == 1) {
                g.setColor(new Color(255, 255, 255, 6));
                g.fillRect(FIELD_X + l * LANE_W, 0, LANE_W, Game.H);
            }
        }
        g.setColor(new Color(255, 255, 255, 14));
        for (int l = 1; l < LANES; l++) g.fillRect(FIELD_X + l * LANE_W, 0, 1, Game.H);
        Color edge = Theme.lerp(Theme.PURPLE_DEEP, Theme.VIOLET, p * 0.8 + milestone * 0.5);
        for (int i = 0; i < 6; i++) {
            g.setColor(Theme.alpha(edge, (0.55 - i * 0.09) * (0.7 + p * 0.3)));
            g.fillRect(FIELD_X - 2 - i, 0, 1, Game.H);
            g.fillRect(FIELD_X + FIELD_W + 1 + i, 0, 1, Game.H);
        }
        if (missFlash > 0.02) {
            g.setColor(Theme.alpha(Judgement.MISS.color, missFlash * 0.12));
            g.fillRect(FIELD_X, 0, FIELD_W, Game.H);
        }

        // bar lines
        double barMs = 240000.0 / song.bpm;
        double off = song.beatOffset * 1000;
        long k0 = (long) Math.ceil((now - off) / barMs);
        g.setColor(new Color(200, 170, 255, 26));
        for (long k = k0; ; k++) {
            double y = yOf(off + k * barMs);
            if (y < 0) break;
            if (y <= HIT_Y) g.fillRect(FIELD_X, (int) y, FIELD_W, 1);
        }

        // key beams
        for (int l = 0; l < LANES; l++) {
            if (beam[l] <= 0.01) continue;
            int x = FIELD_X + l * LANE_W;
            Color c = Theme.lane(l);
            g.setPaint(new GradientPaint(0, HIT_Y, Theme.alpha(c, 0.42 * beam[l]), 0, HIT_Y - 330, Theme.alpha(c, 0)));
            g.fillRect(x + 1, HIT_Y - 330, LANE_W - 1, 330);
        }

        // notes
        Composite oc = g.getComposite();
        for (int l = 0; l < LANES; l++) {
            List<Chart.Note> ln = lanes[l];
            int x = FIELD_X + l * LANE_W;
            Color c = Theme.lane(l);
            for (int i = drawIdx[l]; i < ln.size(); i++) {
                Chart.Note n = ln.get(i);
                double y = yOf(n.time);
                if (y < -NOTE_H * 2) break;
                if (n.headDone && !n.hold && !n.dead) continue;
                if (n.hold) {
                    if (n.tailDone && !n.dead) continue;
                    double yh = n.holding ? HIT_Y : y;
                    double ye = yOf(n.end);
                    if (ye > Game.H + 40 && yh > Game.H + 40) continue;
                    drawHoldBody(g, x, ye, yh, c, n.holding, n.dead);
                }
                if (n.dead) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
                double hy = (n.hold && n.holding) ? HIT_Y : y;
                if (!(n.headDone && !n.dead && !n.holding)) {
                    g.drawImage(noteImg[l], x + 5 - PAD, (int) Math.round(hy - NOTE_H / 2.0) - PAD, null);
                }
                if (n.dead) g.setComposite(oc);
            }
        }

        // top fade (lane cover)
        g.setPaint(new GradientPaint(0, 0, new Color(4, 2, 10, 255), 0, 90, new Color(4, 2, 10, 0)));
        g.fillRect(FIELD_X, 0, FIELD_W, 90);

        // receptors
        for (int l = 0; l < LANES; l++) {
            int x = FIELD_X + l * LANE_W;
            Color c = Theme.lane(l);
            boolean dn = down[l];
            double sq = dn ? 0.92 : 1.0;
            double w = (LANE_W - 10) * sq, h = (NOTE_H + 4) * sq;
            double cx = x + LANE_W / 2.0;
            RoundRectangle2D rr = new RoundRectangle2D.Double(cx - w / 2, HIT_Y - h / 2, w, h, 14, 14);
            g.setColor(Theme.alpha(c, dn ? 0.4 : 0.08 + p * 0.06));
            g.fill(rr);
            g.setStroke(new BasicStroke(2.2f));
            g.setColor(Theme.alpha(Theme.lerp(c, Color.WHITE, dn ? 0.4 : 0), dn ? 1 : 0.65));
            g.draw(rr);
            if (flash[l] > 0.02) {
                g.setColor(Theme.alpha(Color.WHITE, flash[l] * 0.55));
                g.fill(rr);
            }
            g.setFont(Theme.font(Font.BOLD, 16));
            g.setColor(dn ? Color.WHITE : Theme.alpha(Theme.TEXT_DIM, 0.8));
            Theme.drawCentered(g, KEY_NAMES[l], cx, HIT_Y + 48);
        }
        g.setStroke(new BasicStroke(1f));

        // rings + particles
        for (double[] r : rings) {
            double a = 1 - r[2] / 0.32;
            double rad = (20 + r[2] * 165) * r[6];
            g.setStroke(new BasicStroke((float) (4 * a + 0.5)));
            g.setColor(new Color((int) r[3], (int) r[4], (int) r[5], (int) (a * 220)));
            g.draw(new Ellipse2D.Double(r[0] - rad, r[1] - rad * 0.55, rad * 2, rad * 1.1));
        }
        g.setStroke(new BasicStroke(1f));
        for (double[] q : parts) {
            double a = q[4] / q[5];
            g.setColor(new Color((int) q[7], (int) q[8], (int) q[9], (int) (Math.min(1, a * 1.4) * 255)));
            double s = q[6] * (0.5 + a * 0.5);
            g.fill(new Ellipse2D.Double(q[0] - s, q[1] - s, s * 2, s * 2));
        }

        renderJudgement(g);
        renderHud(g);
        if (game.settings.showErrorBar) renderErrorBar(g);

        // intro / banners
        if (now < firstNote - 300 && !paused) {
            double a = Math.min(1, (firstNote - 300 - now) / 400);
            g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 40));
            Theme.glowText(g, "GET READY", Game.W / 2.0, 300, Theme.alpha(Color.WHITE, a), Theme.alpha(Theme.PURPLE, a), 4);
            g.setFont(Theme.font(Font.PLAIN, 16));
            g.setColor(Theme.alpha(Theme.TEXT_DIM, a));
            Theme.drawCentered(g, song.title + "  •  " + diff.label, Game.W / 2.0, 334);
        }
        if (banner != null && finishTimer >= 0) {
            double a = Math.min(1, finishTimer * 4);
            double s = 1 + 0.3 * Math.max(0, 1 - finishTimer * 5);
            AffineTransform at = g.getTransform();
            g.translate(Game.W / 2.0, 330);
            g.scale(s, s);
            g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 58));
            Color core = banner.startsWith("ALL") ? new Color(0xe0f2fe) : Theme.VIOLET;
            Theme.glowText(g, banner, 0, 0, Theme.alpha(core, a), Theme.alpha(Theme.CYAN, a), 8);
            g.setTransform(at);
        }
        if (paused) renderPause(g);
    }

    private void drawHoldBody(Graphics2D g, int x, double yEnd, double yHead, Color c, boolean active, boolean dead) {
        double top = Math.max(-20, yEnd), bot = Math.min(Game.H + 20, yHead);
        if (bot <= top) return;
        double bw = LANE_W - 34, bx = x + (LANE_W - bw) / 2.0;
        Color base = dead ? new Color(90, 80, 110) : c;
        double al = dead ? 0.25 : active ? 0.75 : 0.5;
        g.setPaint(new GradientPaint((float) bx, 0, Theme.alpha(base, al * 0.55), (float) (bx + bw / 2), 0, Theme.alpha(base, al), true));
        g.fill(new RoundRectangle2D.Double(bx, top, bw, bot - top, 10, 10));
        if (active) {
            g.setColor(new Color(255, 255, 255, 40));
            double ph = (t * 600) % 28;
            Shape oc = g.getClip();
            g.clip(new Rectangle2D.Double(bx, top, bw, bot - top));
            for (double y = bot + ph - 28; y > top - 28; y -= 28) g.fill(new Rectangle2D.Double(bx, y, bw, 8));
            g.setClip(oc);
            g.setColor(Theme.alpha(Color.WHITE, 0.6));
            g.fill(new Rectangle2D.Double(bx + bw / 2 - 1, top, 2, bot - top));
        }
        // tail cap
        g.setColor(Theme.alpha(Theme.lerp(base, Color.WHITE, 0.3), dead ? 0.3 : 0.95));
        g.fill(new RoundRectangle2D.Double(bx - 6, yEnd - 5, bw + 12, 10, 8, 8));
    }

    private void renderJudgement(Graphics2D g) {
        double cx = FIELD_X + FIELD_W / 2.0;
        if (combo >= 3) {
            double s = 1 + comboAnim * 0.22;
            AffineTransform at = g.getTransform();
            g.translate(cx, 262);
            g.scale(s, s);
            g.setFont(Theme.font(Font.BOLD, 62));
            Color cc = Theme.lerp(Theme.TEXT, Theme.CYAN, Math.round(milestone * 4) / 4.0);
            Theme.glowText(g, String.valueOf(combo), 0, 0, Theme.alpha(cc, 0.92), Theme.alpha(Theme.PURPLE, 0.8), 4);
            g.setTransform(at);
            g.setFont(Theme.font(Font.BOLD, 13));
            g.setColor(Theme.alpha(Theme.VIOLET, 0.75));
            Theme.drawCentered(g, "COMBO", cx, 286);
        }
        if (lastJ != null && jAge < 0.7) {
            double a = jAge < 0.42 ? 1 : Math.max(0, 1 - (jAge - 0.42) / 0.28);
            double s = 1 + 0.38 * Math.max(0, 1 - jAge / 0.085);
            Color c = lastJ.color;
            if (lastJ == Judgement.MARVELOUS) c = Theme.lerp(new Color(0x7dd3fc), new Color(0xf0abfc), Math.round((0.5 + 0.5 * Math.sin(t * 18)) * 5) / 5.0);
            AffineTransform at = g.getTransform();
            g.translate(cx, 360);
            g.scale(s, s);
            g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 36));
            Theme.glowText(g, lastJ.label, 0, 0, Theme.alpha(c, a), Theme.alpha(c, a * 0.8), 3);
            g.setTransform(at);
            if (showEL) {
                g.setFont(Theme.font(Font.BOLD, 13));
                boolean early = lastErr < 0;
                g.setColor(Theme.alpha(early ? Theme.BLUE : Theme.PINK, a * 0.9));
                Theme.drawCentered(g, (early ? "EARLY " : "LATE +") + Math.round(Math.abs(lastErr)) + "ms", cx, 384);
            }
        }
    }

    private void renderHud(Graphics2D g) {
        // progress
        double len = Math.max(1, chart.lastTime());
        double prog = Math.max(0, Math.min(1, now / len));
        g.setColor(new Color(255, 255, 255, 16));
        g.fillRect(0, 0, Game.W, 4);
        g.setPaint(new GradientPaint(0, 0, Theme.PURPLE, Game.W, 0, Theme.CYAN));
        g.fillRect(0, 0, (int) (Game.W * prog), 4);

        // song info (top-left)
        g.setFont(Theme.font(Font.BOLD, 22));
        g.setColor(Theme.TEXT);
        g.drawString(song.title, 36, 52);
        g.setFont(Theme.font(Font.PLAIN, 15));
        g.setColor(Theme.TEXT_DIM);
        g.drawString(song.artist, 36, 74);
        g.setFont(Theme.font(Font.BOLD, 14));
        g.setColor(diff.color);
        g.drawString(diff.label + String.format("  ★ %.2f", chart.stars), 36, 98);
        if (auto) { g.setColor(Theme.GOLD); g.drawString("AUTOPLAY", 36, 122); }

        // score (top-right)
        g.setFont(Theme.font(Font.BOLD, 44));
        g.setColor(Color.WHITE);
        Theme.drawRight(g, String.format("%07d", score()), Game.W - 36, 64);
        g.setFont(Theme.font(Font.BOLD, 24));
        g.setColor(Theme.VIOLET);
        Theme.drawRight(g, String.format("%.2f%%", accuracy()), Game.W - 36, 98);
        g.setFont(Theme.font(Font.PLAIN, 14));
        g.setColor(Theme.TEXT_DIM);
        Theme.drawRight(g, "max combo " + maxCombo, Game.W - 36, 122);

        // judgement counters (left of field)
        double x = FIELD_X - 210, y = 430;
        for (int i = 0; i < 6; i++) {
            Judgement j = Judgement.values()[i];
            g.setFont(Theme.font(Font.BOLD, 13));
            g.setColor(Theme.alpha(j.color, 0.85));
            g.drawString(j.label, (float) x, (float) (y + i * 24));
            g.setFont(Theme.font(Font.BOLD, 15));
            g.setColor(Theme.TEXT);
            Theme.drawRight(g, String.valueOf(counts[i]), x + 170, y + i * 24);
        }

        // spectrum-reactive side meters (right of field)
        double sx = FIELD_X + FIELD_W + 30;
        double lvl = game.bg.level();
        for (int i = 0; i < 16; i++) {
            double v = game.bg.spectrum[i * 3];
            double bh = 4 + v * 70;
            g.setColor(Theme.alpha(Theme.lerp(Theme.PURPLE, Theme.CYAN, i / 15.0), 0.25 + v * 0.5));
            g.fill(new RoundRectangle2D.Double(sx + i * 9, 560 - bh, 6, bh, 3, 3));
        }
        g.setFont(Theme.font(Font.PLAIN, 11));
        g.setColor(Theme.alpha(Theme.TEXT_FAINT, 0.5 + lvl * 0.5));
        g.drawString("ESC pause  •  ` retry", (float) sx, 586);
    }

    private void renderErrorBar(Graphics2D g) {
        double cx = Game.W / 2.0, y = 690, half = 150;
        double maxW = diff.badWindow();
        for (int j = 4; j >= 0; j--) {
            Judgement jj = Judgement.values()[j];
            double w = diff.window(jj) / maxW * half;
            g.setColor(Theme.alpha(jj.color, 0.28));
            g.fill(new Rectangle2D.Double(cx - w, y - 3, w * 2, 6));
        }
        for (int i = 0; i < tickErr.length; i++) {
            if (tickJ[i] == null || tickAge[i] > 3) continue;
            double a = Math.max(0, 1 - tickAge[i] / 3);
            double x = cx + Math.max(-1, Math.min(1, tickErr[i] / maxW)) * half;
            g.setColor(Theme.alpha(tickJ[i].color, a));
            g.fill(new Rectangle2D.Double(x - 1, y - 10, 2.5, 20));
        }
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Double(cx - 1, y - 13, 2, 26));
        double ax = cx + Math.max(-1, Math.min(1, avgErr / maxW)) * half;
        Path2D tri = new Path2D.Double();
        tri.moveTo(ax, y - 15); tri.lineTo(ax - 6, y - 23); tri.lineTo(ax + 6, y - 23); tri.closePath();
        g.setColor(Theme.CYAN);
        g.fill(tri);
    }

    private void renderPause(Graphics2D g) {
        g.setColor(new Color(4, 0, 12, 200));
        g.fillRect(0, 0, Game.W, Game.H);
        double cx = Game.W / 2.0;
        g.setFont(Theme.font(Font.BOLD | Font.ITALIC, 64));
        Theme.glowText(g, "PAUSED", cx, 250, Theme.TEXT, Theme.PURPLE, 6);
        String[] items = {"RESUME", "RETRY", "QUIT TO SONG SELECT"};
        for (int i = 0; i < 3; i++) {
            boolean s = i == pauseSel;
            double y = 340 + i * 60;
            if (s) {
                g.setPaint(new GradientPaint((float) (cx - 200), 0, Theme.alpha(Theme.PURPLE, 0.05), (float) cx, 0, Theme.alpha(Theme.PURPLE, 0.45), true));
                g.fill(new RoundRectangle2D.Double(cx - 200, y - 32, 400, 46, 22, 22));
            }
            g.setFont(Theme.font(Font.BOLD, s ? 26 : 22));
            g.setColor(s ? Color.WHITE : Theme.TEXT_DIM);
            Theme.drawCentered(g, items[i], cx, y);
        }
    }
}
