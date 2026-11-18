package voidbeat;

import java.awt.Color;

enum Judgement {
    MARVELOUS("MARVELOUS", 320, 300, new Color(0x7dd3fc)),
    PERFECT("PERFECT", 300, 300, new Color(0xd8b4fe)),
    GREAT("GREAT", 200, 200, new Color(0x818cf8)),
    GOOD("GOOD", 100, 100, new Color(0x34d399)),
    BAD("BAD", 50, 50, new Color(0xfbbf24)),
    MISS("MISS", 0, 0, new Color(0xf43f5e));

    final String label;
    final int score;
    final int acc;
    final Color color;

    Judgement(String label, int score, int acc, Color color) {
        this.label = label;
        this.score = score;
        this.acc = acc;
        this.color = color;
    }
}
