package com.anvil;

import java.util.Locale;

/**
 * One way to fold an OSRS name into a comparison key — the SAME way the site does it.
 *
 * <p>Every use of this is the same shape: take a name the client read out of the game, take a name
 * the server sent, and decide whether they are the same account. That only works if both sides fold
 * identically. There were three of these in the plugin, agreeing with each other about half the
 * time and with the server never.</p>
 *
 * <p><b>The rule</b> is the server's ({@code Anvil.Site src/lib/auth.ts normalizeRsn}):
 * lower-case, and collapse every run of whitespace <em>or underscore</em> to one space.</p>
 *
 * <p><b>Underscores are the part that was missing.</b> OSRS treats space and underscore as the same
 * character in a name: the game renders "GIM Nisbro", while logins, hiscores lookups and
 * hand-entered roster rows carry "GIM_nisbro". The server folds them together deliberately —
 * without it a roster sync reports one member left and another joined for the same person. The
 * plugin did not, so any name holding an underscore on the site failed to match the account playing
 * it: no bounty credit, no drop attribution, no "that's you" in a standings list.</p>
 *
 * <p><b>Two places where Java quietly disagrees with JavaScript</b>, both of which this has to bridge
 * because the server is the JavaScript one:</p>
 * <ul>
 *   <li>{@code String.trim()} in Java strips only characters up to U+0020, so it leaves a
 *       non-breaking space where JS strips it. OSRS names are full of U+00A0 — that is how the game
 *       encodes their spaces so a name never wraps a line. Folded to a plain space first.</li>
 *   <li>Java's {@code \\s} does not match U+00A0 either; JS's does. Spelled out in the class.</li>
 * </ul>
 *
 * <p>{@link Locale#ROOT} rather than the default locale, and that is not pedantry: a Turkish client
 * lower-cases 'I' to 'ı', so "IRON MIKE" would fold to something the server has never seen and that
 * player would silently stop matching their own roster row.</p>
 */
public final class Rsn
{
	private Rsn()
	{
	}

	/** Whitespace-or-underscore runs. U+00A0 is named explicitly — Java's {@code \s} omits it. */
	private static final java.util.regex.Pattern FOLD =
		java.util.regex.Pattern.compile("[\\s\\u00a0_]+");

	/**
	 * The comparison key for a name, or "" when there is no name.
	 *
	 * <p>Never null, because every caller compares the result and a null check at each of them is a
	 * check somebody eventually forgets. An empty key never matches a real one — callers guard on
	 * {@code !me.isEmpty()} before comparing, so an unknown local name matches nobody rather than
	 * everybody.</p>
	 */
	public static String normalize(String rsn)
	{
		if (rsn == null)
		{
			return "";
		}
		// U+00A0 → space BEFORE trimming, so a name padded with them trims like it does on the server.
		String plain = rsn.replace('\u00A0', ' ').trim().toLowerCase(Locale.ROOT);
		return FOLD.matcher(plain).replaceAll(" ").trim();
	}

	/** Do these two names denote the same account? Empty never matches, including empty itself. */
	public static boolean same(String a, String b)
	{
		String left = normalize(a);
		return !left.isEmpty() && left.equals(normalize(b));
	}
}
