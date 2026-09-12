package com.anvil.util;

import java.util.Locale;

/**
 * Coins, as a person would say them, for the chat lines and Discord posts the plugin writes.
 *
 * <p>"1.5M gp" and "15k gp" — one decimal on millions, none on thousands. Deliberately NOT
 * {@code QuantityFormatter}: RuneLite's rounds differently ("1.50M", "15.9K"), and these strings go
 * into posts that sit in a clan's Discord next to older ones.
 *
 * <p>{@link java.util.Locale#ROOT} because a comma for a decimal point turns "1,5M gp" into
 * something the reader has to stop and parse.
 *
 * <p>{@link #tally} is the OTHER format, and the difference is deliberate: it is a column in a
 * task list ("12.5K", "2.1B") rather than a sentence, so it carries no unit, keeps a decimal only
 * where one earns its place, and clamps a negative to zero — a task's running total is never below
 * nothing, and "-5" in that column reads as a bug.
 */
public final class Gp
{
    private Gp()
    {
    }

    /** Short human gp label for proof banners / logs (5.0M gp, 500k gp, 999 gp). */
    public static String format(long gp) {
        if (gp >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM gp", gp / 1_000_000.0);
        }
        if (gp >= 1_000) {
            return String.format(Locale.ROOT, "%.0fk gp", gp / 1_000.0);
        }
        return gp + " gp";
    }

    /**
     * Squeeze a gp amount into the int the progress bar is drawn from. A gp target is a long because
     * a clan-wide one can pass 2.1b; the bar only needs the ratio, and both ends clamp together so a
     * clamped tile still fills at the right rate rather than looking finished early.
     */
    /**
     * A gp amount as an int a progress bar can use: clamped to zero below, and to Integer.MAX_VALUE
     * above — a stack worth more than two billion still fills the bar exactly once.
     */
    public static int clamp(long gp)
    {
        if (gp <= 0)
        {
            return 0;
        }
        return gp > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) gp;
    }

    /**
     * Short gp for a progress line: 999, 12.5K, 3.4M, 2.15B. Trailing ".0" is dropped so a round
     * number reads "50M" rather than "50.0M", and the unit is only attached once — this sits in
     * "12.5M/50M", where repeating it on both sides is noise.
     */
    public static String tally(long gp)
    {
        long v = Math.max(0, gp);
        if (v < 1_000)
        {
            return Long.toString(v);
        }
        String unit;
        double scaled;
        if (v < 1_000_000)
        {
            unit = "K";
            scaled = v / 1_000d;
        }
        else if (v < 1_000_000_000L)
        {
            unit = "M";
            scaled = v / 1_000_000d;
        }
        else
        {
            unit = "B";
            scaled = v / 1_000_000_000d;
        }
        // One decimal, but only when it says something — truncated rather than rounded so a tile
        // never reads as finished ("50M/50M") while the server still counts it short.
        double truncated = Math.floor(scaled * 10) / 10d;
        if (truncated == Math.floor(truncated))
        {
            return (long) truncated + unit;
        }
        return String.format(Locale.ROOT, "%.1f%s", truncated, unit);
    }
}
