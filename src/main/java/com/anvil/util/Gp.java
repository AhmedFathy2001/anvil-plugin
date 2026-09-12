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
}
