package voidbeat;

import java.util.*;

/**
 * Procedural composer. Each built-in track is defined by tempo, key, chord progression, style and
 * section layout; the arranger writes drums, bass, chords, arps and a generated (but repeating,
 * phrase-structured) lead melody, and emits every musical hit as a {@link ChartEvent}.
 */
final class Arranger {
    private Arranger() {}

    enum Style { HOUSE, FUTURE, HARD, DNB }
    enum Sec { INTRO, VERSE, BUILD, DROP, BREAK, OUTRO }

    static final int[] MINOR = {0, 2, 3, 5, 7, 8, 10};

    record Def(String id, String title, String artist, double bpm, int root, int[] prog, Style style,
               Sec[] secs, int[] bars, long seed) {}

    private static final Sec I = Sec.INTRO, V = Sec.VERSE, B = Sec.BUILD, D = Sec.DROP, K = Sec.BREAK, O = Sec.OUTRO;

    static final List<Def> DEFS = List.of(
            new Def("lilac-static", "Lilac Static", "VOIDBEAT Originals", 100, 48, new int[]{0, 3, 6, 2},
                    Style.HOUSE, new Sec[]{I, V, B, D, K, B, D, O}, new int[]{4, 8, 2, 8, 4, 2, 8, 4}, 11),
            new Def("violet-hour", "Violet Hour", "VOIDBEAT Originals", 124, 45, new int[]{0, 5, 2, 6},
                    Style.HOUSE, new Sec[]{I, V, B, D, K, B, D, O}, new int[]{4, 8, 4, 16, 8, 4, 16, 4}, 7),
            new Def("neon-requiem", "Neon Requiem", "VOIDBEAT Originals", 140, 41, new int[]{0, 5, 6, 4},
                    Style.FUTURE, new Sec[]{I, V, B, D, K, B, D, O}, new int[]{4, 8, 4, 16, 8, 4, 16, 4}, 23),
            new Def("ultraviolet-rush", "Ultraviolet Rush", "VOIDBEAT Originals", 160, 40, new int[]{0, 3, 5, 6},
                    Style.HARD, new Sec[]{I, V, B, D, K, B, D, O}, new int[]{4, 8, 4, 16, 8, 8, 16, 4}, 42),
            new Def("abyssal-bloom", "Abyssal Bloom", "VOIDBEAT Originals", 174, 38, new int[]{0, 6, 5, 4},
                    Style.DNB, new Sec[]{I, V, B, D, K, B, D, O}, new int[]{8, 8, 4, 16, 8, 4, 16, 4}, 99)
    );

    // ------------------------------------------------------------------ melody
    private static final class Motif {
        final int[] steps, degs, durs;
        Motif(int[] s, int[] d, int[] u) { steps = s; degs = d; durs = u; }
        Motif copy() { return new Motif(steps.clone(), degs.clone(), durs.clone()); }
    }

    private static double[] rhythmProbs(Style s) {
        return switch (s) {
            case HOUSE -> new double[]{0.95, 0.75, 0.5, 0.14};
            case FUTURE -> new double[]{0.95, 0.65, 0.45, 0.12};
            case HARD -> new double[]{0.95, 0.85, 0.55, 0.05};
            case DNB -> new double[]{0.95, 0.75, 0.38, 0.0};
        };
    }

    private static Motif genMotif(Random r, Style style) {
        double[] pr = rhythmProbs(style);
        List<Integer> on = new ArrayList<>();
        for (int s = 0; s < 32; s++) {
            int cls = s % 8 == 0 ? 0 : s % 4 == 0 ? 1 : s % 2 == 0 ? 2 : 3;
            if (s == 0 || r.nextDouble() < pr[cls]) on.add(s);
        }
        while (on.size() < 9) {
            int s = r.nextInt(16) * 2;
            if (!on.contains(s)) on.add(s);
        }
        Collections.sort(on);
        int n = on.size();
        int[] steps = new int[n], degs = new int[n], durs = new int[n];
        int[] moves = {-2, -1, -1, 0, 1, 1, 2, -3, 3, 4, -1, 1};
        int d = 7 + r.nextInt(3) * 2;
        for (int i = 0; i < n; i++) {
            steps[i] = on.get(i);
            if (i > 0) d = Math.max(4, Math.min(15, d + moves[r.nextInt(moves.length)]));
            degs[i] = d;
            int next = i + 1 < n ? on.get(i + 1) : 32;
            durs[i] = Math.max(1, next - steps[i]);
        }
        return new Motif(steps, degs, durs);
    }

    private static Motif vary(Motif m, Random r, boolean resolve) {
        Motif v = m.copy();
        int n = v.degs.length;
        for (int i = Math.max(1, n - 3); i < n; i++) v.degs[i] = Math.max(4, Math.min(15, v.degs[i] + r.nextInt(5) - 2));
        if (resolve) v.degs[n - 1] = 7;
        return v;
    }

    private static int snap(int deg, int chord) {
        for (int off = 0; off <= 3; off++) {
            for (int sg : new int[]{-1, 1}) {
                int d = deg + sg * off;
                int rel = Math.floorMod(d - chord, 7);
                if (rel == 0 || rel == 2 || rel == 4) return d;
            }
        }
        return deg;
    }

    private static int note(int root, int deg) {
        return root + MINOR[Math.floorMod(deg, 7)] + 12 * Math.floorDiv(deg, 7);
    }

    // ------------------------------------------------------------------ render
    static SongEntry placeholder(Def def) {
        SongEntry e = new SongEntry(def.id, def.title, def.artist, "ORIGINAL");
        e.bpm = def.bpm;
        e.beatOffset = 0;
        e.lengthSec = Arrays.stream(def.bars).sum() * 240.0 / def.bpm + 2.0;
        e.def = def;
        return e;
    }

    static void render(Def def, SongEntry e) {
        double spb = 60.0 / def.bpm, bar = spb * 4, st = spb / 4;
        int totalBars = Arrays.stream(def.bars).sum();
        Synth sy = new Synth(totalBars * bar + 3.5, def.seed);
        Random r = new Random(def.seed);
        Motif A = genMotif(r, def.style);
        Motif A2 = vary(A, r, false);
        Motif Bm = vary(A, r, true);
        Motif[] phrase = {A, A2, A, Bm};
        List<ChartEvent> ev = e.events;
        Style style = def.style;
        int root = def.root;
        boolean firstDrop = true;
        int dropNo = 0;

        int b = 0;
        for (int si = 0; si < def.secs.length; si++) {
            Sec sec = def.secs[si];
            int bars = def.bars[si];
            if (sec == Sec.DROP && firstDrop) { e.previewSec = b * bar; firstDrop = false; }
            if (sec == Sec.BUILD) sy.riser(b * bar, bars * bar, 1.0);
            for (int k = 0; k < bars; k++, b++) {
                double t0 = b * bar;
                int chord = def.prog[b % def.prog.length];
                int energy = switch (sec) { case DROP -> 2; case VERSE, BUILD -> 1; default -> 0; };
                drums(sy, ev, style, sec, k, bars, t0, st, energy);

                // pads
                int[] triad = {note(root, chord) + 12, note(root, chord + 2) + 12, note(root, chord + 4) + 12, note(root, chord + 7) + 12};
                double padVel = switch (sec) { case DROP -> 0.55; case INTRO, BREAK -> 1.25; default -> 0.9; };
                sy.pad(t0, bar, triad, padVel);
                if (sec == Sec.INTRO || sec == Sec.BREAK || sec == Sec.OUTRO)
                    ev.add(new ChartEvent(ChartEvent.Kind.PAD, t0, bar, triad[0], 0.62, energy));

                // bass
                int bn = note(root, chord);
                if (bn > root + 7) bn -= 12;
                if (sec == Sec.VERSE || sec == Sec.DROP || (sec == Sec.OUTRO && k < bars / 2)
                        || (sec == Sec.INTRO && style == Style.HARD && k >= bars / 2)) {
                    bassBar(sy, ev, style, t0, st, bn, sec == Sec.DROP ? 1.0 : 0.75, energy);
                }

                // arps
                if (sec == Sec.VERSE || (sec == Sec.BUILD && k < bars - 1)) {
                    boolean sixteenth = style == Style.HOUSE || style == Style.HARD;
                    int every = sixteenth ? 1 : 2;
                    int[] chordDegs = {chord, chord + 2, chord + 4, chord + 7};
                    int idx = 0;
                    for (int s = 0; s < 16; s += every, idx++) {
                        int pitch = note(root, chordDegs[idx % 4]) + 12 + ((k % 2 == 1 && idx % 8 >= 4) ? 12 : 0);
                        double vel = s % 4 == 0 ? 1.0 : 0.7;
                        sy.pluck(t0 + s * st, pitch, vel, (idx % 2 == 0) ? -0.35 : 0.35);
                        double w = s % 4 == 0 ? 0.62 : s % 2 == 0 ? 0.46 : 0.36;
                        ev.add(new ChartEvent(ChartEvent.Kind.ARP, t0 + s * st, st * every, pitch, w, energy));
                    }
                }

                // lead
                if (sec == Sec.DROP) {
                    Motif m = phrase[(k / 2) % 4];
                    int half = k % 2;
                    for (int i = 0; i < m.steps.length; i++) {
                        int s = m.steps[i] - half * 16;
                        if (s < 0 || s >= 16) continue;
                        int deg = m.steps[i] % 8 == 0 ? snap(m.degs[i], chord) : m.degs[i];
                        int pitch = note(root, deg) + 12;
                        double dur = m.durs[i] * st * (m.durs[i] == 1 ? 0.75 : 0.92);
                        double t = t0 + s * st;
                        sy.lead(t, dur, pitch, 1.0, 1.0);
                        if (dropNo > 0) sy.lead(t, dur, pitch + 12, 0.42, 0.8);
                        double w = s % 4 == 0 ? 1.0 : s % 2 == 0 ? 0.8 : 0.7;
                        ev.add(new ChartEvent(ChartEvent.Kind.LEAD, t, m.durs[i] * st, pitch, w, energy));
                    }
                } else if (sec == Sec.BREAK && k >= 2) {
                    Motif m = phrase[(k / 2) % 4];
                    int half = k % 2;
                    int[] longSteps = (k % 2 == 0) ? new int[]{0, 8} : new int[]{0};
                    for (int j = 0; j < longSteps.length; j++) {
                        int s = longSteps[j];
                        int len = longSteps.length == 2 ? 8 : 16;
                        int degRaw = 7;
                        for (int i = 0; i < m.steps.length; i++) if (m.steps[i] <= s + half * 16) degRaw = m.degs[i];
                        int pitch = note(root, snap(degRaw, chord)) + 12;
                        double t = t0 + s * st;
                        sy.lead(t, len * st * 0.95, pitch, 0.7, 0.35);
                        ev.add(new ChartEvent(ChartEvent.Kind.LEAD, t, len * st, pitch, 0.92, energy));
                    }
                }
            }
            if (sec == Sec.DROP) dropNo++;
        }
        e.lengthSec = totalBars * bar + 2.0;
        e.pulses.addAll(sy.kicks);
        Collections.sort(e.pulses);
        e.audio = sy.mixdown(spb * 0.75, 3.0);
        e.ready = true;
    }

    private static void drums(Synth sy, List<ChartEvent> ev, Style style, Sec sec, int k, int bars,
                              double t0, double st, int energy) {
        boolean four = style == Style.HOUSE || style == Style.HARD;
        double punch = style == Style.HARD ? 1.7 : 1.0;
        ChartEvent.Kind KICK = ChartEvent.Kind.KICK, SN = ChartEvent.Kind.SNARE, HAT = ChartEvent.Kind.HAT;

        if (sec == Sec.DROP && k % 8 == 0) {
            sy.crash(t0, 1.0);
            if (k == 0) sy.impact(t0, 1.0);
            ev.add(new ChartEvent(ChartEvent.Kind.CRASH, t0, 0, -1, 1.2, energy));
        }

        if (sec == Sec.BUILD) {
            if (four && k < bars / 2) for (int s = 0; s < 16; s += 4) kick(sy, ev, t0 + s * st, 0.9, punch, energy);
            int interval = k < bars / 2 ? 4 : (k < bars - 1 ? 2 : 1);
            for (int s = 0; s < 16; s += interval) {
                if (k == bars - 1 && s >= 14) break;
                double prog = (k * 16 + s) / (double) (bars * 16);
                sy.snare(t0 + s * st, 0.35 + 0.65 * prog);
                ev.add(new ChartEvent(SN, t0 + s * st, 0, -1, 0.42 + 0.4 * prog, energy));
            }
            return;
        }
        if (sec == Sec.BREAK) {
            if (k >= bars / 2) for (int s = 2; s < 16; s += 4) {
                sy.hat(t0 + s * st, 0.45, false, 0.2);
                ev.add(new ChartEvent(HAT, t0 + s * st, 0, -1, 0.2, energy));
            }
            return;
        }
        boolean lastOutro = sec == Sec.OUTRO && k == bars - 1;
        if (lastOutro) {
            kick(sy, ev, t0, 1.0, punch, energy);
            sy.crash(t0, 0.8);
            ev.add(new ChartEvent(ChartEvent.Kind.CRASH, t0, 0, -1, 1.1, energy));
            return;
        }
        boolean snares = sec != Sec.INTRO;
        boolean drop = sec == Sec.DROP;

        if (four) {
            for (int s = 0; s < 16; s += 4) kick(sy, ev, t0 + s * st, 1.0, punch, energy);
            if (snares) for (int s : new int[]{4, 12}) {
                sy.clap(t0 + s * st, 1.0);
                if (style == Style.HARD || drop) sy.snare(t0 + s * st, 0.7);
                ev.add(new ChartEvent(SN, t0 + s * st, 0, -1, 0.82, energy));
            }
            if (k >= 1 || sec != Sec.INTRO) for (int s = 2; s < 16; s += 4) {
                sy.hat(t0 + s * st, drop ? 0.8 : 0.6, drop, 0.15);
                ev.add(new ChartEvent(HAT, t0 + s * st, 0, -1, drop ? 0.32 : 0.24, energy));
            }
            if (drop || sec == Sec.VERSE) for (int s = 1; s < 16; s += 2) {
                sy.hat(t0 + s * st, 0.3, false, s % 4 == 1 ? -0.4 : 0.4);
                ev.add(new ChartEvent(HAT, t0 + s * st, 0, -1, 0.16, energy));
            }
        } else if (style == Style.FUTURE) {
            int[] kicks = drop ? (k % 2 == 0 ? new int[]{0, 7, 10} : new int[]{0, 10, 14}) : new int[]{0, 10};
            for (int s : kicks) kick(sy, ev, t0 + s * st, 1.0, 1.15, energy);
            if (snares) {
                sy.clap(t0 + 8 * st, 1.0);
                sy.snare(t0 + 8 * st, 0.8);
                ev.add(new ChartEvent(SN, t0 + 8 * st, 0, -1, 0.9, energy));
            }
            if (k >= 1 || sec != Sec.INTRO) {
                for (int s = 0; s < 16; s += 2) {
                    sy.hat(t0 + s * st, s % 4 == 2 ? 0.7 : 0.45, false, 0.2);
                    ev.add(new ChartEvent(HAT, t0 + s * st, 0, -1, s % 4 == 2 ? 0.26 : 0.18, energy));
                }
                if (drop && k % 2 == 1) for (int j = 0; j < 8; j++) {   // trap 1/32 roll
                    double t = t0 + (12 + j * 0.5) * st;
                    sy.hat(t, 0.35 + j * 0.06, false, -0.3);
                    if (j % 2 == 0) ev.add(new ChartEvent(HAT, t, 0, -1, 0.3, energy));
                }
            }
        } else { // DNB
            int[] kicks = (k % 2 == 0) ? new int[]{0, 10} : new int[]{0, 6, 10};
            for (int s : kicks) kick(sy, ev, t0 + s * st, 1.0, 1.1, energy);
            if (snares) {
                for (int s : new int[]{4, 12}) {
                    sy.snare(t0 + s * st, 1.0);
                    sy.clap(t0 + s * st, 0.5);
                    ev.add(new ChartEvent(SN, t0 + s * st, 0, -1, 0.88, energy));
                }
                if (drop) {
                    sy.snare(t0 + 7 * st, 0.25);
                    sy.snare(t0 + 15 * st, 0.22);
                    ev.add(new ChartEvent(SN, t0 + 7 * st, 0, -1, 0.3, energy));
                }
            }
            if (k >= 1 || sec != Sec.INTRO) for (int s = 0; s < 16; s += 2) {
                sy.hat(t0 + s * st, s % 4 == 2 ? 0.75 : 0.5, false, s % 4 == 2 ? 0.3 : -0.3);
                ev.add(new ChartEvent(HAT, t0 + s * st, 0, -1, s % 4 == 2 ? 0.24 : 0.17, energy));
            }
            if (drop) for (int s = 1; s < 16; s += 2) sy.hat(t0 + s * st, 0.22, false, 0.5);
        }
    }

    private static void kick(Synth sy, List<ChartEvent> ev, double t, double vel, double punch, int energy) {
        sy.kick(t, vel, punch);
        ev.add(new ChartEvent(ChartEvent.Kind.KICK, t, 0, -1, energy == 2 ? 0.76 : 0.72, energy));
    }

    private static void bassBar(Synth sy, List<ChartEvent> ev, Style style, double t0, double st, int bn,
                                double vel, int energy) {
        switch (style) {
            case HOUSE -> {
                for (int s = 2; s < 16; s += 4) {
                    sy.bassNote(t0 + s * st, st * 1.6, bn, vel, 0);
                    ev.add(new ChartEvent(ChartEvent.Kind.BASS, t0 + s * st, st * 2, bn, 0.4, energy));
                }
                if (energy == 2) sy.bassNote(t0 + 15 * st, st * 0.8, bn + 12, vel * 0.8, 0);
            }
            case FUTURE -> {
                sy.bassNote(t0, st * 9.5, bn, vel, 1);
                sy.bassNote(t0 + 10 * st, st * 5.5, bn, vel, 1);
                ev.add(new ChartEvent(ChartEvent.Kind.BASS, t0 + 10 * st, st * 6, bn, 0.4, energy));
            }
            case HARD -> {
                for (int s = 0; s < 16; s++) {
                    if (s % 4 == 0) continue;
                    sy.bassNote(t0 + s * st, st * 0.75, bn, vel * (s % 4 == 2 ? 1 : 0.8), 2);
                }
                for (int s = 2; s < 16; s += 4)
                    ev.add(new ChartEvent(ChartEvent.Kind.BASS, t0 + s * st, st, bn, 0.38, energy));
            }
            case DNB -> {
                sy.bassNote(t0, st * 9.6, bn, vel, 3);
                sy.bassNote(t0 + 10 * st, st * 5.6, bn + (energy == 2 ? 12 : 0), vel, 3);
                ev.add(new ChartEvent(ChartEvent.Kind.BASS, t0 + 10 * st, st * 6, bn, 0.4, energy));
            }
        }
    }
}
