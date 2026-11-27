package com.anvil;

import java.util.regex.*;

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
class PersonalBestParser {
	/** An [h:]mm:ss[.cc] token. The game never prints a bare seconds count for a PB. */
	static final Pattern TIME = Pattern.compile("(\\d{1,3}):(\\d{2})(?::(\\d{2}))?(?:\\.(\\d{1,2}))?");
	/** "personal best: 7:59" — the explicit form, where this run was NOT the best. */
	static final Pattern EXPLICIT = Pattern.compile("personal best:\\s*");

	/**
	 * The personal best this line reports, in centiseconds, or null when it reports none.
	 */
	static Integer parseCentis(String message) {
		if (message == null) return null;
		var lower = message.toLowerCase();
		int marker = lower.indexOf("personal best");
		if (marker < 0) return null;

		var explicit = EXPLICIT.matcher(lower);
		var time = TIME.matcher(lower);
		// "Personal best: <time>" — read the time AFTER the label, and only that one. Else "(new
		// personal best)" — this run IS the best, so the line's own duration is the number.
		return (explicit.find(marker) ? time.find(explicit.end()) : time.find()) ? toCentis(time) : null;
	}

	static Integer toCentis(Matcher m) {
		try {
			int centis = TimedClearParser.clockSeconds(m.group(1), m.group(2), m.group(3)) * 100;
			var fraction = m.group(4);
			if (fraction != null) {
				// ".3" is three tenths — pad to hundredths rather than reading it as three.
				centis += Integer.parseInt(fraction) * (fraction.length() == 1 ? 10 : 1);
			}
			return centis > 0 ? centis : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
