package com.anvil;

/**
 * What the plugin says in chat about its own setup, and how often.
 *
 * <p>THE STATE THAT SAID NOTHING. Somebody who installed Anvil and never opened the side panel used
 * to get complete silence forever: the plugin's whole surface is a panel they have no reason to
 * click, and nothing in the game ever mentioned that there was a step left. There is one setting to
 * have now — the Account Token, which Sign in fills in — so the only question is "signed in yet?".</p>
 *
 * <p>That mattered less when Anvil was something your clan admin set up for you and handed you a
 * token for. It is now a per-person account that works with no clan at all, so a fresh install is
 * the normal way in rather than a clan's leftover, and the thing it needs to know is where to go.</p>
 *
 * <p>NUDGE, NOT NAG. The first-run line is capped — {@link #FIRST_RUN_NUDGES} logins, counted in
 * config so the cap survives a restart — because somebody who has decided not to connect has
 * answered the question, and a message that repeats forever is how a plugin gets uninstalled.</p>
 *
 * <p>Pure, and RuneLite-free, so the decision can be tested without a client: the plugin supplies
 * the token and the count, and gets back what to print.</p>
 */
public final class SetupNudge
{
	/** How many logins a never-configured install is told where to go before it stops asking. */
	static final int FIRST_RUN_NUDGES = 3;

	/** The site, as it reads in a chat line — one source of truth with the address the plugin uses. */
	static final String SITE_HOST = BingoApiClient.CANONICAL_SITE.replaceFirst("^https?://", "");

	public enum Kind
	{
		/** Connected, or done asking. Say nothing. */
		NONE,
		/** Not signed in — the fresh install. */
		FIRST_RUN,
	}

	/**
	 * @param firstRunShown how many times the first-run line has already been printed on this install
	 */
	public static Kind decide(String token, int firstRunShown)
	{
		if (token != null && !token.trim().isEmpty())
		{
			return Kind.NONE;
		}
		return firstRunShown < FIRST_RUN_NUDGES ? Kind.FIRST_RUN : Kind.NONE;
	}

	/**
	 * The chat lines for a decision, in order. Empty for {@link Kind#NONE}.
	 *
	 * <p>The first-run pair is two lines on purpose: the first is the instruction — one button, in a
	 * panel they are being told exists — and the second is the reason anyone would press it, which
	 * for a person with no clan is the part that is not obvious. Neither line is a request to go
	 * anywhere but the one site this plugin talks to.</p>
	 */
	public static String[] lines(Kind kind)
	{
		switch (kind)
		{
			case FIRST_RUN:
				return new String[]{
					"Not connected yet — open the Anvil panel (the anvil icon on the right) and press \"Sign in with Discord\".",
					"A free account on " + SITE_HOST + " tracks your collection log, personal bests and records. No clan needed — and starting one is free.",
				};
			default:
				return new String[0];
		}
	}

	private SetupNudge()
	{
	}
}
