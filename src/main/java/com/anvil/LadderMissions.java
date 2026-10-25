package com.anvil;

/**
 * Pure, RuneLite-free helpers for the in-game "missions board" of a ladder / rotating event: the live
 * grow-or-decay value of a mission, the per-second countdown to the next reveal, and their display
 * strings. Kept side-effect-free so the Swing sidebar renders
 * one implementation and it's unit-testable. No threads, no I/O — just clock math.
 */
final class LadderMissions
{
	private LadderMissions()
	{
	}

	/** True for the ladder format (individual-primary random-task leaderboard). Null/other → false. */
	static boolean isLadder(String format)
	{
		return "ladder".equalsIgnoreCase(format);
	}

	/**
	 * A mission's live value = face scaled by the decay ramp: {@code face * (1 - (1 - target) * frac)},
	 * where {@code target = targetPct/100} and {@code frac} is the elapsed fraction of the ramp window
	 * (0 at reveal → 1 after {@code hours}). {@code targetPct < 100} decays, {@code > 100} grows. Returns
	 * the plain face when there's no decay rule or the reveal time is unknown/unparseable.
	 */
	static long liveValue(long face, String revealedAtIso, PluginConfigResponse.Decay decay, long nowMs)
	{
		if (decay == null || decay.hours <= 0)
		{
			return face;
		}
		long revealedAt = Ui.utcMillis(revealedAtIso);
		if (revealedAt < 0)
		{
			return face;
		}
		double target = decay.targetPct / 100.0;
		double frac = Math.min(1.0, Math.max(0.0, (nowMs - revealedAt) / (decay.hours * 3_600_000.0)));
		double value = face * (1.0 - (1.0 - target) * frac);
		return Math.round(Math.max(0.0, value));
	}

	/**
	 * "120 -> 90" (decay) / "120 -> 200" (grow) when the value has moved, else just "120". ASCII arrow:
	 * the OSRS chat + collection-log widget fonts don't carry Unicode arrows/emoji.
	 */
	static String valueLabel(long face, long current)
	{
		if (current == face)
		{
			return Long.toString(face);
		}
		return face + " -> " + current;
	}

	/**
	 * Per-second countdown to the next reveal: {@code "12:34"} (m:ss), {@code "1:02:03"} (h:mm:ss for
	 * long waits), {@code "now"} once due, or {@code null} when there's no scheduled reveal (e.g. a
	 * bounty board draws on claim, not a clock).
	 */
	static String countdown(String nextRevealAtIso, long nowMs)
	{
		long at = Ui.utcMillis(nextRevealAtIso);
		if (at < 0)
		{
			return null;
		}
		long secs = Math.max(0, (at - nowMs) / 1000);
		if (secs == 0)
		{
			return "now";
		}
		long h = secs / 3600;
		long m = (secs % 3600) / 60;
		long s = secs % 60;
		if (h > 0)
		{
			return h + ":" + two(m) + ":" + two(s);
		}
		return m + ":" + two(s);
	}

	private static String two(long v)
	{
		return v < 10 ? "0" + v : Long.toString(v);
	}
}
