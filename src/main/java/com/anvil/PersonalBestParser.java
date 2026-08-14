package com.anvil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a personal best out of a completion message.
 *
 * <p>Two shapes, and the difference matters — reading the wrong number would record a slow run as
 * somebody's best:
 * <pre>
 *   "Duration: 2:58.03 (new personal best)"          → the PB is the duration, 2:58.03
 *   "Challenge duration: 10:24. Personal best: 7:59." → the PB is 7:59, NOT this run's 10:24
 * </pre>
 *
 * <p>Times are returned in <b>centiseconds</b>. The game prints hundredths and a leaderboard that
 * rounded to whole seconds would tie runs the game itself separates.
 *
 * <p>The activity is deliberately NOT parsed here: the plugin already extracts it from the adjacent
 * "Your X kill count is: N" line for kill crediting, so PB capture reuses that instead of running a
 * second regex over every line of chat. RuneLite-free, so the fragile part is unit-tested.
 */
final class PersonalBestParser
{
	private PersonalBestParser() {}

	/** An [h:]mm:ss[.cc] token. The game never prints a bare seconds count for a PB. */
	private static final Pattern TIME = Pattern.compile("(\\d{1,3}):(\\d{2})(?::(\\d{2}))?(?:\\.(\\d{1,2}))?");
	/** "personal best: 7:59" — the explicit form, where this run was NOT the best. */
	private static final Pattern EXPLICIT = Pattern.compile("personal best:\\s*");

	/**
	 * The personal best this line reports, in centiseconds, or null when it reports none.
	 */
	static Integer parseCentis(String message)
	{
		if (message == null)
		{
			return null;
		}
		String lower = message.toLowerCase();
		int marker = lower.indexOf("personal best");
		if (marker < 0)
		{
			return null;
		}

		// "Personal best: <time>" — read the time AFTER the label, and only that one.
		Matcher explicit = EXPLICIT.matcher(lower);
		if (explicit.find(marker))
		{
			Matcher time = TIME.matcher(lower);
			if (time.find(explicit.end()))
			{
				return toCentis(time);
			}
			return null;
		}

		// "(new personal best)" — this run IS the best, so the line's own duration is the number.
		Matcher time = TIME.matcher(lower);
		if (time.find())
		{
			return toCentis(time);
		}
		return null;
	}

	private static Integer toCentis(Matcher m)
	{
		try
		{
			int a = Integer.parseInt(m.group(1));
			int b = Integer.parseInt(m.group(2));
			// Three parts means h:mm:ss; two means mm:ss.
			int seconds = m.group(3) != null
				? a * 3600 + b * 60 + Integer.parseInt(m.group(3))
				: a * 60 + b;
			int centis = seconds * 100;
			String fraction = m.group(4);
			if (fraction != null)
			{
				// ".3" is three tenths — pad to hundredths rather than reading it as three.
				centis += fraction.length() == 1
					? Integer.parseInt(fraction) * 10
					: Integer.parseInt(fraction);
			}
			return centis > 0 ? centis : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
