package voidbeat;

import java.awt.Color;

/**
 * Hit windows (± ms) per judgement. HARD uses the exact osu!mania OD8 formula
 * (MAX 16, 300 = 64-3*OD, 200 = 97-3*OD, 100 = 127-3*OD, 50 = 151-3*OD, miss = 188-3*OD),
 * EXPERT is OD9, NORMAL is a bit softer than OD5, and EASY is far more forgiving than OD0.
 */
enum Difficulty {
    //           name      MARV PERF GREAT GOOD BAD  MISS  colour                  forgiving
    EASY("EASY",     new double[]{28, 85, 125, 160, 190, 225}, new Color(0x34d399), true),
    NORMAL("NORMAL", new double[]{18, 52,  86, 116, 141, 178}, new Color(0x38bdf8), false),
    HARD("HARD",     new double[]{16, 40,  73, 103, 127, 164}, new Color(0xa855f7), false),
    EXPERT("EXPERT", new double[]{16, 37,  70, 100, 124, 161}, new Color(0xf472b6), false);

    final String label;
    final double[] windows;
    final Color color;
    /** Forgiving mode: early ghost taps never count as a miss. */
    final boolean forgiving;

    Difficulty(String label, double[] windows, Color color, boolean forgiving) {
        this.label = label;
        this.windows = windows;
        this.color = color;
        this.forgiving = forgiving;
    }

    double window(Judgement j) { return windows[j.ordinal()]; }
    double missWindow() { return windows[5]; }
    double badWindow() { return windows[4]; }

    /** Judgement for an absolute timing error, or null if outside the BAD window. */
    Judgement judge(double absErr, double scale) {
        for (int i = 0; i < 5; i++) if (absErr <= windows[i] * scale) return Judgement.values()[i];
        return null;
    }
}
