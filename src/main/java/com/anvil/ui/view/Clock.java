package com.anvil.ui.view;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * How long until something starts, or until it ends.
 *
 * <p>The sidebar says the one thing that matters about the clock right now — when an event starts if
 * it hasn't, otherwise when it ends — and says it in the coarsest unit that still tells you
 * something: minutes under an hour, hours and minutes under a day, days and hours beyond that.</p>
 *
 * <p>Everything here answers null rather than guessing when the date is missing or unparseable. A
 * sidebar with no countdown reads as "we don't know"; one showing a made-up number does not.</p>
 */
public final class Clock
{
	private Clock()
	{
	}

	/** " · ends in 3h 0m", lower-cased to sit inside a sentence. Empty when there is no answer. */
	public static String timingSuffix(boolean upcoming, String startIso, String endIso)
	{
		String label = timingLabel(upcoming, startIso, endIso);
		return label == null ? "" : " · " + label.substring(0, 1).toLowerCase() + label.substring(1);
	}

	/** What matters about the clock right now: when it starts if it hasn't, else when it ends. */
	public static String timingLabel(boolean upcoming, String startIso, String endIso)
	{
		return upcoming ? gapLabel("Starts in ", startIso, "Starting…") : endsInLabel(endIso);
	}

	/** "Ends in 2d 4h" / "Ends in 42m" / "Ended", or null when the date is missing or unparseable. */
	public static String endsInLabel(String endIso)
	{
		return gapLabel("Ends in ", endIso, "Ended");
	}

	/** "{prefix}2d 4h" until {@code iso}; {@code passed} once it's behind us; null when unparseable. */
	private static String gapLabel(String prefix, String iso, String passed)
	{
		long at = epochMillis(iso);
		if (at < 0)
		{
			return null;
		}
		long left = at - System.currentTimeMillis();
		if (left <= 0)
		{
			return passed;
		}
		long mins = left / 60_000;
		if (mins < 60)
		{
			return prefix + Math.max(1, mins) + "m";
		}
		long hours = mins / 60;
		if (hours < 24)
		{
			return prefix + hours + "h " + (mins % 60) + "m";
		}
		return prefix + (hours / 24) + "d " + (hours % 24) + "h";
	}

	/** ISO date ("2026-06-21") or UTC datetime → epoch millis, or -1 when unparseable. */
	public static long epochMillis(String iso)
	{
		if (iso == null || iso.length() < 10)
		{
			return -1;
		}
		try
		{
			if (iso.length() == 10)
			{
				return LocalDate.parse(iso)
					.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
			}
			String s = iso.trim().replace(' ', 'T');
			return Instant.parse(s.endsWith("Z") ? s : s + "Z").toEpochMilli();
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}
}
