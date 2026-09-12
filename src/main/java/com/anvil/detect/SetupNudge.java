package com.anvil.detect;

import com.anvil.api.BingoApiClient;
import com.anvil.clog.model.Kind;

/**
 * What the plugin says in chat about its own setup, and how often.
 *
 * <p>THE STATE THAT SAID NOTHING. The old check compared "has a Site URL" against "has a token" and
 * returned when they agreed — which covers the connected install, and covers the fresh one too. So
 * somebody who installed Anvil and never opened the side panel got complete silence forever: the
 * plugin's whole surface is a panel they have no reason to click, and nothing in the game ever
 * mentioned that there was a step left. A half-configured install got told; the one that had done
 * nothing at all did not.</p>
 *
 * <p>That mattered less when Anvil was something your clan admin set up for you and handed you a
 * token for. It is now a per-person account that works with no clan at all, so a fresh install is
 * the normal way in rather than a clan's leftover, and the thing it needs to know is where to go.</p>
 *
 * <p>NUDGE, NOT NAG. The first-run line is capped — {@link #FIRST_RUN_NUDGES} logins, counted in
 * config so the cap survives a restart — because somebody who has decided not to connect has
 * answered the question, and a message that repeats forever is how a plugin gets uninstalled. The
 * two half-configured lines are unchanged: those are a real misconfiguration, they are once per
 * client session, and they are what the member asked for by configuring half of it.</p>
 *
 * <p>Pure, and RuneLite-free, so the decision can be tested without a client: the plugin supplies
 * the two config values and the count, and gets back what to print.</p>
 */
public final class SetupNudge
{
	/** How many logins a never-configured install is told where to go before it stops asking. */
	static final int FIRST_RUN_NUDGES = 3;

	/** The site, as it reads in a chat line — one source of truth with the address Sign in writes. */
	static final String SITE_HOST = BingoApiClient.CANONICAL_SITE.replaceFirst("^https?://", "");

	public enum Kind
	{
		/** Connected, or done asking. Say nothing. */
		NONE,
		/** Nothing configured at all — the fresh install, and the only one of these that is not an error. */
		FIRST_RUN,
		/** Site URL set, no token: tracking cannot authenticate. */
		MISSING_TOKEN,
		/** Token set, no Site URL: tracking has nowhere to go. */
		MISSING_URL,
	}

	/**
	 * @param firstRunShown how many times the first-run line has already been printed on this install
	 */
	public static Kind decide(String apiUrl, String token, int firstRunShown)
	{
		boolean hasUrl = apiUrl != null && !apiUrl.trim().isEmpty();
		boolean hasToken = token != null && !token.trim().isEmpty();

		if (hasUrl && hasToken)
		{
			return Kind.NONE;
		}
		if (hasUrl)
		{
			return Kind.MISSING_TOKEN;
		}
		if (hasToken)
		{
			return Kind.MISSING_URL;
		}
		return firstRunShown < FIRST_RUN_NUDGES ? Kind.FIRST_RUN : Kind.NONE;
	}

	/**
	 * The chat lines for a decision, in order. Empty for {@link Kind#NONE}.
	 *
	 * <p>The first-run pair is two lines on purpose: the first is the instruction — one button, in a
	 * panel they are being told exists — and the second is the reason anyone would press it, which
	 * for a person with no clan is the part that is not obvious. Neither line is a request to go
	 * anywhere the plugin has not already been pointed: signing in is what writes the address.</p>
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
			case MISSING_URL:
				return new String[]{
					"Your Account Token is set but the Site URL is missing — add it in the Anvil plugin config so tracking can connect.",
				};
			case MISSING_TOKEN:
				return new String[]{
					"Your Site URL is set but the Account Token is missing — paste your token from the Anvil site into the plugin config.",
				};
			default:
				return new String[0];
		}
	}

	private SetupNudge()
	{
	}
}
